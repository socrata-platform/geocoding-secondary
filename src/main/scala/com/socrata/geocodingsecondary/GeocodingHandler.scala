package com.socrata.geocodingsecondary

import com.rojoma.json.v3.ast.{JNull, JString, JValue}
import com.rojoma.json.v3.util.{AutomaticJsonCodecBuilder, WrapperFieldCodec}
import com.socrata.datacoordinator.id.{StrategyType, ColumnId, UserColumnId}
import com.socrata.datacoordinator.secondary
import com.socrata.datacoordinator.secondary._
import com.socrata.datacoordinator.secondary.feedback.{ComputationFailure, CookieSchema, ComputationHandler, RowComputeInfo}
import com.socrata.geocoders.{LatLon, Geocoder, Address}
import com.socrata.soql.types._

case class GeocodeRowInfo(address: Address, data: secondary.Row[SoQLValue], targetColId: UserColumnId) extends RowComputeInfo[SoQLValue]

class GeocodingHandler(geocoder: Geocoder, retries: Int) extends ComputationHandler[SoQLType, SoQLValue, GeocodeRowInfo] {

  override def user: String = "geocoding-secondary"

  override def computationRetries: Int = retries

  override def matchesStrategyType(typ: StrategyType): Boolean = typ.underlying == "geocoding"

  case class ParameterSchema(address: Option[UserColumnId],
                             city: Option[UserColumnId],
                             state: Option[UserColumnId],
                             zip: Option[UserColumnId],
                             country: Option[UserColumnId])

  object ParameterSchema {
    implicit val userColumnIdCodec = WrapperFieldCodec[UserColumnId](new UserColumnId(_), _.underlying)
    implicit val parameterSchemaCodec = AutomaticJsonCodecBuilder[ParameterSchema]
  }

  override def transform(row: Row[SoQLValue], targetColId: UserColumnId, strategy: ComputationStrategyInfo, cookie: CookieSchema): GeocodeRowInfo = {
    val parameters = ParameterSchema.parameterSchemaCodec.decode(strategy.parameters) match {
      case Right(result) => result
      case Left(error) => throw new InternalError(s"Failed to parse parameters: ${error.english}")
    }

    val address = extractColumnValue(row, parameters.address)(cookie)
    val city = extractColumnValue(row, parameters.city)(cookie)
    val state = extractColumnValue(row, parameters.state)(cookie)
    val zip = extractColumnValue(row, parameters.zip)(cookie)
    val country = extractColumnValue(row, parameters.country)(cookie)

    GeocodeRowInfo(Address(address, city, state, zip, country), row, targetColId)
  }

  private def extractColumnValue(row: secondary.Row[SoQLValue], colId: Option[UserColumnId], canBeNumber: Boolean = false)(cookie: CookieSchema): Option[String] = {
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
