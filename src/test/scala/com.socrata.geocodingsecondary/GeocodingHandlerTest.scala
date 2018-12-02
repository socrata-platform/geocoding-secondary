package com.socrata.geocodingsecondary

import com.socrata.datacoordinator.secondary.feedback.{ComputationError, ComputationFailure}
import com.socrata.datacoordinator.util.collection.ColumnIdMap
import com.socrata.geocoders.{InternationalAddress, LatLon, OptionalGeocoder}
import com.socrata.soql.types.{SoQLID, SoQLNull, SoQLPoint}
import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory}
import org.scalatest.{FunSuite, ShouldMatchers}

class GeocodingHandlerTest extends FunSuite with ShouldMatchers {

  import TestData._

  val user = "geocoding-secondary"

  val badAddressException = new Exception("Bad address!")

  val geocoder = new OptionalGeocoder {override def batchSize: Int = 100 // doesn't matter

    override def geocode(addresses: Seq[Option[InternationalAddress]]): Seq[Option[LatLon]] =
      addresses.map{ address =>
        if (address == badAddress) throw badAddressException else knownAddresses.get(address)
      }
  }

  val handler = new GeocodingHandler(geocoder)

  test("Accepts computation strategies of type \"geocoding\"") {
    handler.matchesStrategyType(geocodingStrategyType) should be(true)
  }

  // tests for GeocodingHandler.transform(.)

  // note: this shouldn't happen with proper validation up stream
  test("Should transform a row missing source columns to an empty address") {
    val strategy = strategyInfo(sourceColumnIds, parameters)
    val expected = GeocodeRowInfo(emptyAddress, baseRow, targetColId, SoQLNull)
    val dsInfo = handler.setupDataset(cookieSchema(strategy))
    val colInfo = handler.setupColumn(dsInfo, strategy, targetColId)
    handler.setupCell(colInfo, baseRow) should be (expected)
  }

  // note: this shouldn't happen with proper validation up stream
  test("Should preserve computed column supplied value if source columns are nulls") {
    val geometryFactory = new GeometryFactory
    val soqlPoint = SoQLPoint(geometryFactory.createPoint(new Coordinate(1, 2)))
    val strategy = strategyInfo(sourceColumnIds, parameters)
    val baseRow = ColumnIdMap(id.id -> SoQLID(5), point.id -> soqlPoint)
    val expected = GeocodeRowInfo(emptyAddress, baseRow, targetColId, soqlPoint)
    val dsInfo = handler.setupDataset(cookieSchema(strategy))
    val colInfo = handler.setupColumn(dsInfo, strategy, targetColId)
    handler.setupCell(colInfo, baseRow) should be (expected)
  }

  // note: the list source columns ids in the computation strategy should match the
  // source column ids given per address part, but this is not currently validated
  // anywhere, so we should handle these cases gracefully

  test("Should transform an row missing source columns with no optional parameters to an empty address") {
    val strategy = strategyInfo(sourceColumnIds, emptyParameters)
    val expected = GeocodeRowInfo(emptyAddress, baseRow, targetColId, SoQLNull)
    val dsInfo = handler.setupDataset(cookieSchema(strategy))
    val colInfo = handler.setupColumn(dsInfo, strategy, targetColId)
    handler.setupCell(colInfo, baseRow) should be (expected)
  }

  test("Should transform a full row with no optional parameters to an empty address") {
    val strategy = strategyInfo(sourceColumnIds, emptyParameters)
    val expected = GeocodeRowInfo(emptyAddress, socrataRow, targetColId, SoQLNull)
    val dsInfo = handler.setupDataset(cookieSchema(strategy))
    val colInfo = handler.setupColumn(dsInfo, strategy, targetColId)
    handler.setupCell(colInfo, socrataRow) should be (expected)
  }

  // i.e. the column ids passed in the computation strategy are not actually used in the computation
  // only the values given in the `parameters` json blob are used
  test("Should transform a full row with no source columns") {
    val strategy = strategyInfo(emptySourceColumnIds, parameters)
    val expected = GeocodeRowInfo(socrataAddress, socrataRow, targetColId, SoQLNull)
    val dsInfo = handler.setupDataset(cookieSchema(strategy))
    val colInfo = handler.setupColumn(dsInfo, strategy, targetColId)
    handler.setupCell(colInfo, socrataRow) should be (expected)
  }

  test("Should transform a row with a full address") {
    val strategy = strategyInfo(sourceColumnIds, parameters)
    val expected = GeocodeRowInfo(socrataAddress, socrataRow, targetColId, SoQLNull)
    val dsInfo = handler.setupDataset(cookieSchema(strategy))
    val colInfo = handler.setupColumn(dsInfo, strategy, targetColId)
    handler.setupCell(colInfo, socrataRow) should be (expected)
  }

  test("Should transform a row with a SoQLNull value") {
    val strategy = strategyInfo(sourceColumnIds, parameters)
    val expected = GeocodeRowInfo(socrataAddressNoRegion, socrataRowNoRegion, targetColId, SoQLNull)
    val dsInfo = handler.setupDataset(cookieSchema(strategy))
    val colInfo = handler.setupColumn(dsInfo, strategy, targetColId)
    handler.setupCell(colInfo, socrataRowNoRegion) should be (expected)
  }

  test("Should transform a row with partial optional parameters") {
    val strategy = strategyInfo(sourceColumnIds, parametersNoPostalCode)
    val expected = GeocodeRowInfo(socrataAddressNoPostalCode, socrataRow, targetColId, SoQLNull)
    val dsInfo = handler.setupDataset(cookieSchema(strategy))
    val colInfo = handler.setupColumn(dsInfo, strategy, targetColId)
    handler.setupCell(colInfo, socrataRow) should be (expected)
  }

  test("Should transform a row with SoQLNumber zip column") {
    val strategy = strategyInfo(sourceColumnIds, parameters)
    val expected = GeocodeRowInfo(socrataAddress, socrataRowPostalCodeAsNumber, targetColId, SoQLNull)
    val dsInfo = handler.setupDataset(cookieSchema(strategy))
    val colInfo = handler.setupColumn(dsInfo, strategy, targetColId)
    handler.setupCell(colInfo, socrataRowPostalCodeAsNumber) should be (expected)
  }

  test("Should transform a row with SoQLNull valued country column and use default") {
    val strategy = strategyInfo(sourceColumnIds, parameters)
    val expected = GeocodeRowInfo(socrataAddress, socrataRowNullCountry, targetColId, SoQLNull)
    val dsInfo = handler.setupDataset(cookieSchema(strategy))
    val colInfo = handler.setupColumn(dsInfo, strategy, targetColId)
    handler.setupCell(colInfo, socrataRowNullCountry) should be (expected)
  }

  test("Should transform a row with no country column") {
    val strategy = strategyInfo(sourceColumnIds, parametersNoCountry)
    val expected = GeocodeRowInfo(socrataAddress, socrataRow, targetColId, SoQLNull)
    val dsInfo = handler.setupDataset(cookieSchema(strategy))
    val colInfo = handler.setupColumn(dsInfo, strategy, targetColId)
    handler.setupCell(colInfo, socrataRow) should be (expected)
  }

  test("Should transform a row using a default parameter value in place of a SoQLNull") {
    val strategy = strategyInfo(sourceColumnIds, parametersDefaultRegion)
    val expected = GeocodeRowInfo(socrataAddress, socrataRowNoRegion, targetColId, SoQLNull)
    val dsInfo = handler.setupDataset(cookieSchema(strategy))
    val colInfo = handler.setupColumn(dsInfo, strategy, targetColId)
    handler.setupCell(colInfo, socrataRowNoRegion) should be (expected)
  }

  test("Should transform a row using a default parameter value with no source column") {
    val strategy = strategyInfo(sourceColumnIds, parametersDefaultRegion)
    val expected = GeocodeRowInfo(socrataAddress, socrataRowNoRegion, targetColId, SoQLNull)
    val dsInfo = handler.setupDataset(cookieSchema(strategy))
    val colInfo = handler.setupColumn(dsInfo, strategy, targetColId)
    handler.setupCell(colInfo, socrataRowNoRegion) should be (expected)
  }

  test("Should transform a row with extra parameters") {
    val strategy = strategyInfo(sourceColumnIds, parametersWithExtra)
    val expected = GeocodeRowInfo(socrataAddress, socrataRow, targetColId, SoQLNull)
    val dsInfo = handler.setupDataset(cookieSchema(strategy))
    val colInfo = handler.setupColumn(dsInfo, strategy, targetColId)
    handler.setupCell(colInfo, socrataRow) should be (expected)
  }

  test("Should throw a MalformedParametersException when transforming a row with malformed parameters") {
    intercept[MalformedParametersException] {
      val strategy = strategyInfo(sourceColumnIds, parametersMalformed)
      val dsInfo = handler.setupDataset(cookieSchema(strategy))
      val colInfo = handler.setupColumn(dsInfo, strategy, targetColId)
      handler.setupCell(colInfo, socrataRow)
    }
  }

  // tests for GeocodingHandler.compute(.)

  test("Should handle compute(.) called with an empty map") {
    handler.compute[Int](Map.empty) should be (Right(Map.empty))
  }

  test("Should compute results for both valid and invalid addresses") {
    val rows = Map(
      0 -> Seq(GeocodeRowInfo(emptyAddress, emptyRow, targetColId, SoQLNull)),
      2 -> Seq(GeocodeRowInfo(socrataAddress, socrataRow, targetColId, SoQLNull)),
      3 -> Seq(GeocodeRowInfo(socrataDCAddress, socrataDCRow, targetColId, SoQLNull)),
      5 -> Seq(GeocodeRowInfo(nowhereAddress, nowhereRow, targetColId, SoQLNull))
    )
    val expected = Right(Map(
      0 -> Map(targetColId -> SoQLNull),
      2 -> Map(targetColId -> socrataPoint),
      3 -> Map(targetColId -> socrataDCPoint),
      5 -> Map(targetColId -> nowhereValue)
    ))
    handler.compute(rows) should equal (expected)
  }

  test("Should return a ComputationFailure if geocoder throws during compute(.)") {
    val expected = Left(ComputationError(badAddressException.getMessage, Some(badAddressException)))
    handler.compute(Map(0 -> Seq(GeocodeRowInfo(badAddress, badAddressRow, targetColId, SoQLNull)))) should be (expected)
  }
}
