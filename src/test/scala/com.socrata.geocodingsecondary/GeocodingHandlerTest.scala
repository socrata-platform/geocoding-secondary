package com.socrata.geocodingsecondary

import com.socrata.datacoordinator.secondary.feedback.ComputationFailure
import com.socrata.geocoders.{OptionalGeocoder, InternationalAddress, LatLon}
import org.scalatest.{ShouldMatchers, FunSuite}

class GeocodingHandlerTest extends FunSuite with ShouldMatchers {

  import TestData._

  val user = "geocoding-secondary"
  val retries = 5

  val geocoder = new OptionalGeocoder {override def batchSize: Int = 100 // doesn't matter

    override def geocode(addresses: Seq[Option[InternationalAddress]]): Seq[Option[LatLon]] =
      addresses.map{ address =>
        if (address == badAddress) throw new Exception("Bad address!") else knownAddresses.get(address)
      }
  }

  val handler = new GeocodingHandler(geocoder, retries = retries)

  test("Reports user as \"geocoding-secondary\"") {
    handler.user should be(user)
  }

  test("Reports correct number of retries") {
    handler.computationRetries should be(retries)
  }

  test("Accepts computation strategies of type \"geocoding\"") {
    handler.matchesStrategyType(geocodingStrategyType) should be(true)
  }

  // tests for GeocodingHandler.transform(.)

  // note: this shouldn't happen with proper validation up stream
  test("Should transform a row missing source columns to an empty address") {
    val strategy = strategyInfo(sourceColumnIds, parameters)
    val expected = GeocodeRowInfo(emptyAddress, baseRow, targetColId)
    handler.transform(baseRow, targetColId, strategy, cookieSchema(strategy)) should be (expected)
  }

  // note: the list source columns ids in the computation strategy should match the
  // source column ids given per address part, but this is not currently validated
  // anywhere, so we should handle these cases gracefully

  test("Should transform an row missing source columns with no optional parameters to an empty address") {
    val strategy = strategyInfo(sourceColumnIds, emptyParameters)
    val expected = GeocodeRowInfo(emptyAddress, baseRow, targetColId)
    handler.transform(baseRow, targetColId, strategy, cookieSchema(strategy)) should be (expected)
  }

  test("Should transform a full row with no optional parameters to an empty address") {
    val strategy = strategyInfo(sourceColumnIds, emptyParameters)
    val expected = GeocodeRowInfo(emptyAddress, socrataRow, targetColId)
    handler.transform(socrataRow, targetColId, strategy, cookieSchema(strategy)) should be (expected)
  }

  // i.e. the column ids passed in the computation strategy are not actually used in the computation
  // only the values given in the `parameters` json blob are used
  test("Should transform a full row with no source columns") {
    val strategy = strategyInfo(emptySourceColumnIds, parameters)
    val expected = GeocodeRowInfo(socrataAddress, socrataRow, targetColId)
    handler.transform(socrataRow, targetColId, strategy, cookieSchema(strategy)) should be (expected)
  }

  test("Should transform a row with a full address") {
    val strategy = strategyInfo(sourceColumnIds, parameters)
    val expected = GeocodeRowInfo(socrataAddress, socrataRow, targetColId)
    handler.transform(socrataRow, targetColId, strategy, cookieSchema(strategy)) should be (expected)
  }

  test("Should transform a row with a SoQLNull value") {
    val strategy = strategyInfo(sourceColumnIds, parameters)
    val expected = GeocodeRowInfo(socrataAddressNoRegion, socrataRowNoRegion, targetColId)
    handler.transform(socrataRowNoRegion, targetColId, strategy, cookieSchema(strategy)) should be (expected)
  }

  test("Should transform a row with partial optional parameters") {
    val strategy = strategyInfo(sourceColumnIds, parametersNoPostalCode)
    val expected = GeocodeRowInfo(socrataAddressNoPostalCode, socrataRow, targetColId)
    handler.transform(socrataRow, targetColId, strategy, cookieSchema(strategy)) should be (expected)
  }

  test("Should transform a row with SoQLNumber zip column") {
    val strategy = strategyInfo(sourceColumnIds, parameters)
    val expected = GeocodeRowInfo(socrataAddress, socrataRowPostalCodeAsNumber, targetColId)
    handler.transform(socrataRowPostalCodeAsNumber, targetColId, strategy, cookieSchema(strategy)) should be (expected)
  }

  test("Should transform a row with SoQLNull valued country column and use default") {
    val strategy = strategyInfo(sourceColumnIds, parameters)
    val expected = GeocodeRowInfo(socrataAddress, socrataRowNullCountry, targetColId)
    handler.transform(socrataRowNullCountry, targetColId, strategy, cookieSchema(strategy)) should be (expected)
  }

  test("Should transform a row with no country column") {
    val strategy = strategyInfo(sourceColumnIds, parametersNoCountry)
    val expected = GeocodeRowInfo(socrataAddress, socrataRow, targetColId)
    handler.transform(socrataRow, targetColId, strategy, cookieSchema(strategy)) should be (expected)
  }

  test("Should transform a row using a default parameter value in place of a SoQLNull") {
    val strategy = strategyInfo(sourceColumnIds, parametersDefaultRegion)
    val expected = GeocodeRowInfo(socrataAddress, socrataRowNoRegion, targetColId)
    handler.transform(socrataRowNoRegion, targetColId, strategy, cookieSchema(strategy)) should be (expected)
  }

  test("Should transform a row using a default parameter value with no source column") {
    val strategy = strategyInfo(sourceColumnIds, parametersDefaultRegion)
    val expected = GeocodeRowInfo(socrataAddress, socrataRowNoRegion, targetColId)
    handler.transform(socrataRowNoRegion, targetColId, strategy, cookieSchema(strategy)) should be (expected)
  }

  test("Should transform a row using a default country parameter") {
    val strategy1 = strategyInfo(sourceColumnIds, parametersDefaultCountry)
    val expected1 = GeocodeRowInfo(socrataAddressUnitedStates, socrataRowUnitedStates, targetColId)
    handler.transform(socrataRowUnitedStates, targetColId, strategy1, cookieSchema(strategy1)) should be (expected1)

    val strategy2 = strategyInfo(sourceColumnIds, parametersOnlyDefaultCountry)
    val expected2 = GeocodeRowInfo(socrataAddressUnitedStates, socrataRowNullCountry, targetColId)
    handler.transform( socrataRowNullCountry, targetColId, strategy2, cookieSchema(strategy2)) should be (expected2)
  }

  test("Should transform a row with extra parameters") {
    val strategy = strategyInfo(sourceColumnIds, parametersWithExtra)
    val expected = GeocodeRowInfo(socrataAddress, socrataRow, targetColId)
    handler.transform(socrataRow, targetColId, strategy, cookieSchema(strategy)) should be(expected)
  }

  test("Should throw a MalformedParametersException when transforming a row with malformed parameters") {
    intercept[MalformedParametersException] {
      val strategy = strategyInfo(sourceColumnIds, parametersMalformed)
      handler.transform(socrataRow, targetColId, strategy, cookieSchema(strategy))
    }
  }

  // tests for GeocodingHandler.compute(.)

  test("Should handle compute(.) called with an empty iterator") {
    handler.compute(Iterator.empty).toSeq should be (Seq.empty)
  }

  test("Should compute results for both valid and invalid addresses") {
    val rows = Iterator(
      (GeocodeRowInfo(emptyAddress, emptyRow, targetColId), 0),
      (GeocodeRowInfo(socrataAddress, socrataRow, targetColId), 2),
      (GeocodeRowInfo(socrataDCAddress, socrataDCRow, targetColId), 3),
      (GeocodeRowInfo(nowhereAddress, nowhereRow, targetColId), 5)
    )
    val expected = Seq(
      ((GeocodeRowInfo(emptyAddress, emptyRow, targetColId), emptyJValue), 0),
      ((GeocodeRowInfo(socrataAddress, socrataRow, targetColId), socrataJValue), 2),
      ((GeocodeRowInfo(socrataDCAddress, socrataDCRow, targetColId), socrataDCJValue), 3),
      ((GeocodeRowInfo(nowhereAddress, nowhereRow, targetColId), nowhereJValue), 5)
    )
    handler.compute(rows).toSeq should equal (expected)
  }

  test("Should throw a ComputationFailure if geocoder throws during compute(.)") {
    intercept[ComputationFailure] {
      handler.compute(Iterator((GeocodeRowInfo(badAddress, badAddressRow, targetColId), 0)))
    }
  }
}
