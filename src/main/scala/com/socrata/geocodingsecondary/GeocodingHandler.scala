package com.socrata.geocodingsecondary

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

  @JsonKeyStrategy(Strategy.Underscore)
  case class ParameterSchema(address: Option[UserColumnId],
                             locality: Option[UserColumnId],
                             subregion: Option[UserColumnId],
                             region: Option[UserColumnId],
                             postalCode: Option[UserColumnId],
                             country: Option[UserColumnId],
                             addressDefault: Option[String],
                             localityDefault: Option[String],
                             subregionDefault: Option[String],
                             regionDefault: Option[String],
                             postalCodeDefault: Option[String],
                             countryDefault: String)

  object ParameterSchema {
    implicit val userColumnIdCodec = WrapperFieldCodec[UserColumnId](new UserColumnId(_), _.underlying)
    implicit val parameterSchemaCodec = AutomaticJsonCodecBuilder[ParameterSchema]
  }

  override def transform(row: Row[SoQLValue], targetColId: UserColumnId, strategy: ComputationStrategyInfo, cookie: CookieSchema): GeocodeRowInfo = {
    val parameters = ParameterSchema.parameterSchemaCodec.decode(strategy.parameters) match {
      case Right(result) => result
      case Left(error) => throw new MalformedParametersException(s"Failed to parse parameters: ${error.english}")
    }

    val address = extractColumnValue(row, parameters.address)(cookie)
    val locality = extractColumnValue(row, parameters.locality)(cookie)
    val subregion = extractColumnValue(row, parameters.subregion)(cookie)
    val region = extractColumnValue(row, parameters.region)(cookie)
    val postalCode = extractColumnValue(row, parameters.postalCode, canBeNumber = true)(cookie) // just in case a postal code column ends up as a number column even though it _really_ shouldn't be
    val country = extractColumnValue(row, parameters.country)(cookie)

    val internationalAddress =
      if (Seq(address, locality, subregion, region, postalCode, country).forall(_.isEmpty)) {
        None
      } else {
        InternationalAddress(
          applyDefault(address, parameters.addressDefault),
          applyDefault(locality, parameters.localityDefault),
          applyDefault(subregion, parameters.subregionDefault),
          applyDefault(region, parameters.regionDefault),
          applyDefault(postalCode, parameters.postalCodeDefault),
          applyDefault(country, Some(parameters.countryDefault)))
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

class MalformedParametersException(reason: String) extends Exception(reason)
