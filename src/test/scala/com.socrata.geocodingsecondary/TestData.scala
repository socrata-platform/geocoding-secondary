package com.socrata.geocodingsecondary

import com.rojoma.json.v3.ast.{JNumber, JNull, JString, JObject}
import com.socrata.datacoordinator.id.{StrategyType, ColumnId, UserColumnId}
import com.socrata.datacoordinator.secondary.ComputationStrategyInfo
import com.socrata.datacoordinator.secondary.feedback.{CopyNumber, DataVersion, CookieSchema}
import com.socrata.datacoordinator.util.collection.ColumnIdMap
import com.socrata.geocoders.{InternationalAddress, LatLon}
import com.socrata.soql.types._
import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory}

object TestData {

  val geocodingStrategyType = StrategyType("geocoding")

  private val geometryFactory = new ThreadLocal[GeometryFactory] {
    override def initialValue = new GeometryFactory()
  }

  def strategyInfo(sourceColumnIds: Seq[UserColumnId], parameters: JObject) =
    ComputationStrategyInfo(geocodingStrategyType, sourceColumnIds, parameters)

  case class Column(id: ColumnId, userId: UserColumnId)

  def column(id: Long, userId: String) = Column(new ColumnId(id), new UserColumnId(userId))

  // id column
  val id = column(1, ":id")

  // source columns
  val address    = column(2, "addr-esss")
  val locality   = column(3, "loca-lity")
  val subregion  = column(4, "subr-egio")
  val region     = column(5, "regi-onnn")
  val postalCode = column(6, "post-alco")
  val country    = column(7, "coun-tryy")

  val sourceColumns = Seq(address, locality, subregion, region, postalCode, country)

  // target column
  val point = column(8, "poin-tttt")
  val targetColId = point.userId

  val columns = Seq(id, address, locality, subregion, region, postalCode, country, point)
  val columnIdMap = columns.map(col => (col.userId, col.id)).toMap

  // source column ids
  val emptySourceColumnIds = Seq.empty
  val sourceColumnIds = sourceColumns.map(_.userId)

  def parametersSchema(sources: JObject, defaults: JObject) = JObject(Map(
    "sources" -> sources,
    "defaults" -> defaults,
    "version" -> JString("v1")
  ))

  val baseAddressDefaults = JObject(Map(
    "country" -> JString("US"))
  )

  // parameters
  val emptyParameters = parametersSchema(JObject.canonicalEmpty, baseAddressDefaults)

  val fullAddressSource = JObject(Map(
    "address" -> JString(address.userId.underlying),
    "locality" -> JString(locality.userId.underlying),
    "region" -> JString(region.userId.underlying),
    "postal_code" -> JString(postalCode.userId.underlying),
    "country" -> JString(country.userId.underlying)
  ))

  val parameters = parametersSchema(fullAddressSource, baseAddressDefaults)

  val parametersNoPostalCode = parametersSchema(
    JObject(Map(
      "address" -> JString(address.userId.underlying),
      "locality" -> JString(locality.userId.underlying),
      "region" -> JString(region.userId.underlying),
      "country" -> JString(country.userId.underlying))),
    baseAddressDefaults
  )

  val parametersNoCountry = parametersSchema(
    JObject(Map(
      "address" -> JString(address.userId.underlying),
      "locality" -> JString(locality.userId.underlying),
      "region" -> JString(region.userId.underlying),
      "postal_code" -> JString(postalCode.userId.underlying))),
    baseAddressDefaults
  )

  val defaultRegion = JObject(Map(
    "region" -> JString("WA"),
    "country" -> JString("US"))
  )

  val parametersDefaultRegion = parametersSchema(
    fullAddressSource,
    defaultRegion
  )

  val parametersOnlyDefaultRegion = parametersSchema(
    JObject(Map(
      "address" -> JString(address.userId.underlying),
      "locality" -> JString(locality.userId.underlying),
      "postal_code" -> JString(postalCode.userId.underlying),
      "country" -> JString(country.userId.underlying))),
    defaultRegion
  )

  val parametersWithExtra = JObject(Map(
    "sources" -> JObject(Map(
      "address" -> JString(address.userId.underlying),
      "locality" -> JString(locality.userId.underlying),
      "region" -> JString(region.userId.underlying),
      "postal_code" -> JString(postalCode.userId.underlying),
      "extra" -> JNumber(5))),
    "defaults" -> JObject(Map(
      "country" -> JString("US"),
      "extra" -> JNull)),
    "version" -> JString("v1"),
    "extra" -> JNull,
    "extra1" -> JString("extra")
  ))

  val parametersMalformed = parametersSchema(
    JObject(Map(
      "address" -> JString(address.userId.underlying),
      "locality" -> JString(locality.userId.underlying),
      "region" -> JNumber(666),
      "postal_code" -> JString(postalCode.userId.underlying),
      "country" -> JString(country.userId.underlying))),
    baseAddressDefaults
  )

  def cookieSchema(strategyInfo: ComputationStrategyInfo) = CookieSchema(
    dataVersion = DataVersion(44),
    copyNumber = CopyNumber(4),
    systemId = id.userId,
    columnIdMap,
    strategyMap = Map(targetColId -> strategyInfo),
    obfuscationKey = "obfuscate".getBytes,
    computationRetriesLeft = 6,
    dataCoordinatorRetriesLeft = 6,
    resync = false
  )

  def textOrNull(opt: Option[String]) = opt.map(SoQLText(_)).getOrElse(SoQLNull)

  def row(addrOpt: Option[InternationalAddress], nullCountry: Boolean = false, postalCodeAsNumber: Boolean = false) =
    addrOpt match {
      case Some(addr) => ColumnIdMap(
        id.id -> SoQLID(5),
        address.id -> textOrNull(addr.address),
        locality.id -> textOrNull(addr.locality),
        subregion.id -> textOrNull(addr.subregion),
        region.id -> textOrNull(addr.region),
        postalCode.id -> { addr.postalCode match {
          case Some(str) => if (postalCodeAsNumber) SoQLNumber(new java.math.BigDecimal(str)) else SoQLText(str)
          case None => SoQLNull
        }},
        country.id -> { if (nullCountry) SoQLNull else SoQLText(addr.country) },
        point.id -> SoQLNull)
      case None =>  ColumnIdMap(
        id.id -> SoQLID(5),
        address.id -> SoQLNull,
        locality.id -> SoQLNull,
        subregion.id -> SoQLNull,
        region.id -> SoQLNull,
        postalCode.id -> SoQLNull,
        country.id -> SoQLNull,
        point.id -> SoQLNull)
    }

  // rows and addresses
  val baseRow = ColumnIdMap(id.id -> SoQLID(5), point.id -> SoQLNull)

  val emptyAddress = InternationalAddress.create(None, None, None, None, None, None) // actually None
  val emptyRow = row(emptyAddress, nullCountry = true)
  val emptyJValue = JNull
  val usJValue = JString("POINT(36.2474412 -113.7152476)")

  val socrataAddress = InternationalAddress.create(Some("705 5th Ave S #600"), Some("Seattle"), None, Some("WA"), Some("98104"), Some("US"))
  val socrataRow = row(socrataAddress)
  val socrataPoint = SoQLPoint(geometryFactory.get().createPoint(new Coordinate(-122.3303628, 47.5964756)))

  val socrataAddressNoRegion = socrataAddress.map(_.copy(region = None))
  val socrataRowNoRegion = row(socrataAddressNoRegion)

  val socrataAddressNoPostalCode = socrataAddress.map(_.copy(postalCode = None))

  val socrataRowPostalCodeAsNumber = row(socrataAddress, nullCountry = false, postalCodeAsNumber = true)

  val socrataRowNullCountry = row(socrataAddress, nullCountry = true)

  val socrataAddressUnitedStates = socrataAddress.map(_.copy(country = "United States"))
  val socrataRowUnitedStates = row(socrataAddressUnitedStates)

  val socrataDCAddress = InternationalAddress.create(Some("1150 17th St NW #200"), Some("Washington"), None, Some("DC"), Some("20036"), Some("US"))
  val socrataDCRow = row(socrataDCAddress)
  val socrataDCPoint = SoQLPoint(geometryFactory.get().createPoint(new Coordinate(-77.0410809, 38.9053532)))

  val nowhereAddress = InternationalAddress.create(Some("101 Nowhere Lane"), None, None, Some("Nowhere Land"), None, Some("USA"))
  val nowhereRow = row(nowhereAddress)
  val nowhereValue = SoQLNull

  val badAddress = InternationalAddress.create(Some("Bad Address Lane"), None, None, None, None, None)
  val badAddressRow = row(badAddress)

  val knownAddresses = Map(
    socrataAddress -> LatLon(47.5964756, -122.3303628),
    socrataDCAddress -> LatLon(38.9053532, -77.0410809)
  )

}
