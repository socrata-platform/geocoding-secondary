package com.socrata.geocodingsecondary

import com.rojoma.json.v3.codec.{JsonDecode, DecodeError}
import com.rojoma.json.v3.ast.{JNull, JString, JValue}
import com.socrata.computation_strategies.{StrategyType => ST, GeocodingParameterSchema}
import com.socrata.datacoordinator.id.{StrategyType, ColumnId, UserColumnId}
import com.socrata.datacoordinator.secondary
import com.socrata.datacoordinator.secondary._
import com.socrata.datacoordinator.secondary.feedback.{ComputationFailure, CookieSchema, ComputationHandler, RowComputeInfo}
import com.socrata.geocoders.{OptionalGeocoder, InternationalAddress, LatLon}
import com.socrata.soql.types._
import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory, Point}

case class GeocodeRowInfo(address: Option[InternationalAddress], data: secondary.Row[SoQLValue], targetColId: UserColumnId) extends RowComputeInfo[SoQLValue]

class GeocodingHandler(geocoder: OptionalGeocoder, retries: Int) extends ComputationHandler[SoQLType, SoQLValue] {
  type RCI = GeocodeRowInfo

  override def user: String = "geocoding-secondary"

  override def computationRetries: Int = retries

  override def matchesStrategyType(typ: StrategyType): Boolean = typ.underlying == ST.Geocoding.name

  override def transform(row: Row[SoQLValue], targetColId: UserColumnId, strategy: ComputationStrategyInfo, cookie: CookieSchema): GeocodeRowInfo = {
    val parameters = JsonDecode[GeocodingParameterSchema[UserColumnId]].decode(strategy.parameters) match {
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
        row.get(cookie.columnIdMap(id)) match {
          case Some(SoQLText(text)) => Some(text)
          case Some(SoQLNumber(number)) => if (canBeNumber) Some(number.toString) else wrongSoQLType(SoQLNumber)
          case Some(SoQLNull) => None
          case Some(other) => wrongSoQLType(other)
          case None => None
        }
      case None => None
    }
  }

  override def compute[RowHandle](sources: Map[RowHandle, Seq[GeocodeRowInfo]]): Map[RowHandle, Map[UserColumnId, SoQLValue]] = {
    val addresses = sources.valuesIterator.flatMap(_.map(_.address)).toSeq
    val points = try {
      geocoder.geocode(addresses)
    } catch {
      case e : Throwable =>
        throw ComputationFailure(e.getMessage)
    }

    // ok, the reassembly will be a little interesting...
    val (result, leftovers) =
      sources.foldLeft((Map.empty[RowHandle, Map[UserColumnId, SoQLValue]], points)) { (accPoints, rowSources) =>
        val (acc, points) = accPoints
        val (row, sources) = rowSources
        val (pointsHere, leftoverPoints) = points.splitAt(sources.length)
        assert(pointsHere.length == sources.length, "Geocoding returned too few results?")
        val soqlValues = (sources,pointsHere).zipped.map { (source, point) =>
          source.targetColId -> point.fold[SoQLValue](SoQLNull) { case LatLon(lat, lon) =>
            SoQLPoint(geometryFactory.get.createPoint(new Coordinate(lon, lat))) // Not at all sure this is correct!
          }
        }.toMap
        val newAcc = acc + (row -> soqlValues)
        (newAcc, leftoverPoints)
      }
    assert(leftovers.isEmpty, "Geocoding returned too many results?")
    result
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
