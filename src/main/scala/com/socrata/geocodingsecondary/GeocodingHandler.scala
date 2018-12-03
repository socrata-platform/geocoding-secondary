package com.socrata.geocodingsecondary

import com.rojoma.json.v3.codec.{JsonDecode, DecodeError}
import com.rojoma.json.v3.ast.{JNull, JString, JValue}
import com.socrata.computation_strategies.{StrategyType => ST, GeocodingParameterSchema}
import com.socrata.datacoordinator.id.{StrategyType, ColumnId, UserColumnId}
import com.socrata.datacoordinator.secondary
import com.socrata.datacoordinator.secondary._
import com.socrata.datacoordinator.secondary.feedback.{HasStrategy, ComputationError, ComputationFailure, CookieSchema, ComputationHandler}
import com.socrata.geocoders.{OptionalGeocoder, InternationalAddress, LatLon}
import com.socrata.soql.types._
import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory}

case class GeocodeColumnInfo(cookie: CookieSchema, strategy: ComputationStrategyInfo, targetColId: UserColumnId) extends HasStrategy {
  val parameters = JsonDecode[GeocodingParameterSchema[UserColumnId]].decode(strategy.parameters) match {
    case Right(result) => result
    case Left(error) => throw new MalformedParametersException(error)
  }

  val address = parameters.sources.address.map(cookie.columnIdMap)
  val locality = parameters.sources.locality.map(cookie.columnIdMap)
  val subregion = parameters.sources.subregion.map(cookie.columnIdMap)
  val region = parameters.sources.region.map(cookie.columnIdMap)
  val postalCode = parameters.sources.postalCode.map(cookie.columnIdMap)
  val country = parameters.sources.country.map(cookie.columnIdMap)

  def extractColumnValue(row: secondary.Row[SoQLValue],
                         colId: Option[ColumnId],
                         canBeNumber: Boolean = false): Option[String] = {
    def wrongSoQLType(other: AnyRef): Nothing = throw new Exception(s"Expected value to be of type $SoQLText but got: $other")
    colId match {
      case Some(id) =>
        row.get(id) match {
          case Some(SoQLText(text)) => Some(text)
          case Some(SoQLNumber(number)) => if (canBeNumber) Some(number.toString) else wrongSoQLType(SoQLNumber)
          case Some(SoQLNull) => None
          case Some(other) => wrongSoQLType(other)
          case None => None
        }
      case None => None
    }
  }
}
case class GeocodeRowInfo(address: Option[InternationalAddress], data: secondary.Row[SoQLValue], targetColId: UserColumnId, targetValue: SoQLValue)

class GeocodingHandler(geocoder: OptionalGeocoder) extends ComputationHandler[SoQLType, SoQLValue] {
  type PerDatasetData = CookieSchema
  type PerColumnData = GeocodeColumnInfo
  type PerCellData = GeocodeRowInfo

  override def matchesStrategyType(typ: StrategyType): Boolean = typ.underlying == ST.Geocoding.name

  override def setupDataset(cookie: CookieSchema): CookieSchema = cookie

  override def setupColumn(cookie: CookieSchema, strategy: ComputationStrategyInfo, targetColId: UserColumnId): GeocodeColumnInfo = {
    new GeocodeColumnInfo(cookie, strategy, targetColId)
  }

  override def setupCell(colInfo: GeocodeColumnInfo, row: Row[SoQLValue]): GeocodeRowInfo = {
    val address = colInfo.extractColumnValue(row, colInfo.address)
    val locality = colInfo.extractColumnValue(row, colInfo.locality)
    val subregion = colInfo.extractColumnValue(row, colInfo.subregion)
    val region = colInfo.extractColumnValue(row, colInfo.region)
    val postalCode = colInfo.extractColumnValue(row, colInfo.postalCode, canBeNumber = true) // just in case a postal code column ends up as a number column even though it _really_ shouldn't be
    val country = colInfo.extractColumnValue(row, colInfo.country)

    val internationalAddress =
      if (Seq(address, locality, subregion, region, postalCode, country).forall(_.isEmpty)) {
        None
      } else {
        InternationalAddress.create(
          address.orElse(colInfo.parameters.defaults.address),
          locality.orElse(colInfo.parameters.defaults.locality),
          subregion.orElse(colInfo.parameters.defaults.subregion),
          region.orElse(colInfo.parameters.defaults.region),
          postalCode.orElse(colInfo.parameters.defaults.postalCode),
          country.orElse(Some(colInfo.parameters.defaults.country)))
      }

    val targetColumnId = colInfo.cookie.columnIdMap(colInfo.targetColId)
    val targetValue = row(targetColumnId)
    GeocodeRowInfo(internationalAddress, row, colInfo.targetColId, targetValue)
  }

  override def compute[RowHandle](sources: Map[RowHandle, Seq[GeocodeRowInfo]]): Either[ComputationFailure, Map[RowHandle, Map[UserColumnId, SoQLValue]]] = {
    val addresses = sources.valuesIterator.flatMap(_.map(_.address)).toSeq
    val points = try {
      geocoder.geocode(addresses)
    } catch {
      case e : Exception =>
        return Left(ComputationError(e.getMessage, Some(e)))
    }

    // ok, the reassembly will be a little interesting...
    val (result, leftovers) =
      sources.foldLeft((Map.empty[RowHandle, Map[UserColumnId, SoQLValue]], points)) { (accPoints, rowSources) =>
        val (acc, points) = accPoints
        val (row, sources) = rowSources
        val (pointsHere, leftoverPoints) = points.splitAt(sources.length)
        assert(pointsHere.length == sources.length, "Geocoding returned too few results?")
        val soqlValues = (sources,pointsHere).zipped.map { (source, point) =>
          val targetVal = source.targetValue match {
            case SoQLNull =>
              point.fold[SoQLValue](SoQLNull) { case LatLon(lat, lon) =>
                SoQLPoint(geometryFactory.get.createPoint(new Coordinate(lon, lat))) // Not at all sure this is correct!
              }
            case tv => tv // Keep origin target value if it is provided (not as null)
              // TODO: Change geocoder.geocode prototype to geocode(addresses, targetValue) so that
              // geocoder can choose to skip geocode earlier instead of ignoring the geocoded result.
              // Hopefully before this change, rows coming from dsmui here are hitting the same cache.
          }
          source.targetColId -> targetVal
        }.toMap
        val newAcc = acc + (row -> soqlValues)
        (newAcc, leftoverPoints)
      }
    assert(leftovers.isEmpty, "Geocoding returned too many results?")
    Right(result)
  }

  private val geometryFactory =
    new ThreadLocal[GeometryFactory] {
      override def initialValue = new GeometryFactory
    }

  private def toJValue(latLon: Option[LatLon]): JValue = latLon match {
    case Some(point) => JString(s"POINT(${point.lat} ${point.lon})")
    case None => JNull
  }
}

class MalformedParametersException(error: DecodeError) extends Exception(s"Failed to parse parameters: ${error.english}")
