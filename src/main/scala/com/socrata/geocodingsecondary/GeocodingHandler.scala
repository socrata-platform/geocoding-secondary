package com.socrata.geocodingsecondary

import com.rojoma.json.v3.codec.DecodeError
import com.rojoma.json.v3.util.{Strategy, JsonKeyStrategy, AutomaticJsonCodecBuilder, WrapperFieldCodec}
import com.rojoma.json.v3.ast.{JNull, JString, JValue}
import com.socrata.datacoordinator.id.{StrategyType, ColumnId, UserColumnId}
import com.socrata.datacoordinator.secondary
import com.socrata.datacoordinator.secondary._
import com.socrata.datacoordinator.secondary.feedback.{ComputationFailure, CookieSchema, ComputationHandler, RowComputeInfo}
import com.socrata.geocoders.{OptionalGeocoder, InternationalAddress, LatLon}
import com.socrata.soql.types._

case class GeocodeRowInfo(address: Option[InternationalAddress], data: secondary.Row[SoQLValue], targetColId: UserColumnId) extends RowComputeInfo[SoQLValue]

class GeocodingHandler(geocoder: OptionalGeocoder, retries: Int) extends ComputationHandler[SoQLType, SoQLValue, GeocodeRowInfo] {

  override def user: String = "geocoding-secondary"

  override def computationRetries: Int = retries

  override def matchesStrategyType(typ: StrategyType): Boolean = typ.underlying == "geocoding"


  case class ParameterSchema(sources: Sources, defaults: Defaults, version: String)

  @JsonKeyStrategy(Strategy.Underscore)
  case class Sources(address: Option[UserColumnId],
                     locality: Option[UserColumnId],
                     subregion: Option[UserColumnId],
                     region: Option[UserColumnId],
                     postalCode: Option[UserColumnId],
                     country: Option[UserColumnId])

  @JsonKeyStrategy(Strategy.Underscore)
  case class Defaults(address: Option[String],
                      locality: Option[String],
                      subregion: Option[String],
                      region: Option[String],
                      postalCode: Option[String],
                      country: String)

  object ParameterSchema {
    implicit val userColumnIdCodec = WrapperFieldCodec[UserColumnId](new UserColumnId(_), _.underlying)
    implicit val sourcesCodec = AutomaticJsonCodecBuilder[Sources]
    implicit val defaultsCodec = AutomaticJsonCodecBuilder[Defaults]
    implicit val parameterSchemaCodec = AutomaticJsonCodecBuilder[ParameterSchema]
  }

  override def transform(row: Row[SoQLValue], targetColId: UserColumnId, strategy: ComputationStrategyInfo, cookie: CookieSchema): GeocodeRowInfo = {
    val parameters = ParameterSchema.parameterSchemaCodec.decode(strategy.parameters) match {
      case Right(result) => result
      case Left(error) => throw new MalformedParametersException(error)
    }

    val address = extractColumnValue(row, parameters.sources.address)(cookie)
    val locality = extractColumnValue(row, parameters.sources.locality)(cookie)
    val subregion = extractColumnValue(row, parameters.sources.subregion)(cookie)
    val region = extractColumnValue(row, parameters.sources.region)(cookie)
    val postalCode = extractColumnValue(row, parameters.sources.postalCode, canBeNumber = true)(cookie) // just in case a postal code column ends up as a number column even though it _really_ shouldn't be
    val country = extractColumnValue(row, parameters.sources.country)(cookie)

    val internationalAddress =
      if (Seq(address, locality, subregion, region, postalCode, country).forall(_.isEmpty)) {
        None
      } else {
        InternationalAddress(
          address.orElse(parameters.defaults.address),
          locality.orElse(parameters.defaults.locality),
          subregion.orElse(parameters.defaults.subregion),
          region.orElse(parameters.defaults.region),
          postalCode.orElse(parameters.defaults.postalCode),
          country.orElse(Some(parameters.defaults.country)))
      }

    GeocodeRowInfo(internationalAddress, row, targetColId)
  }

  private def extractColumnValue(row: secondary.Row[SoQLValue],
                                 colId: Option[UserColumnId],
                                 canBeNumber: Boolean = false)(cookie: CookieSchema): Option[String] = {
    def wrongSoQLType(other: AnyRef): Nothing = throw new Exception(s"Expected value to be of type $SoQLText but got: $other")
    colId match {
      case Some(id) =>
        row.get(new ColumnId(cookie.columnIdMap(id))) match {
          case Some(SoQLText(text)) => Some(text)
          case Some(SoQLNumber(number)) => if (canBeNumber) Some(number.toString) else wrongSoQLType(SoQLNumber)
          case Some(SoQLNull) => None
          case Some(other) => wrongSoQLType(other)
          case None => None
        }
      case None => None
    }
  }

  private def applyDefault(value: Option[String], default: Option[String]): Option[String] = {
    if (value.isDefined) value
    else default
  }

  override def compute(sources: Iterator[(GeocodeRowInfo, Int)]): Iterator[((GeocodeRowInfo, JValue), Int)] = {
    val sourcesSeq = sources.toSeq
    val addresses = sourcesSeq.map { case (info, _) => info.address }
    val points = try {
      geocoder.geocode(addresses)
    } catch {
      case e : Throwable =>
        throw ComputationFailure(e.getMessage)
    }

    sourcesSeq.zip(points).map { case ((info, index), point) =>
      ((info, toJValue(point)), index)
    }.toIterator
  }

  private def toJValue(latLon: Option[LatLon]): JValue = latLon match {
    case Some(point) => JString(s"POINT(${point.lat} ${point.lon})")
    case None => JNull
  }
}

class MalformedParametersException(error: DecodeError) extends Exception(s"Failed to parse parameters: ${error.english}")
