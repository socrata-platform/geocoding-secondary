package com.socrata.geocodingsecondary

import com.rojoma.json.v3.ast._
import com.rojoma.json.v3.codec.JsonDecode
import com.rojoma.json.v3.util.WrapperJsonCodec
import com.socrata.datacoordinator.id.{ColumnId, StrategyType, UserColumnId}
import com.socrata.computation_strategies.{StrategyType => ST, GeoRegionMatchOnStringParameterSchema, GeoRegionMatchOnPointParameterSchema}
import com.socrata.datacoordinator.secondary.{ComputationStrategyInfo, Row}
import com.socrata.datacoordinator.secondary.feedback.{HasStrategy, CookieSchema, ComputationHandler}
import com.socrata.soql.environment.ColumnName
import com.socrata.soql.types._

case class ResourceName(underlying: String)
object ResourceName extends (String => ResourceName) {
  implicit val jCodec = WrapperJsonCodec[ResourceName](this, _.underlying)
}

trait AbstractRegionCodeColumnInfo extends HasStrategy {
  implicit val columnNameCodec = WrapperJsonCodec[ColumnName](ColumnName, _.name)
  def defaultRegionPrimaryKey = ColumnName("_feature_id")

  def targetColId: ColumnId
  def userTargetColId: UserColumnId
  def endpoint: String
}

case class RegionCodePointColumnInfo(strategy: ComputationStrategyInfo, targetColId: ColumnId, userTargetColId: UserColumnId) extends AbstractRegionCodeColumnInfo {
  val parameters = JsonDecode[GeoRegionMatchOnPointParameterSchema[ResourceName, ColumnName]].decode(strategy.parameters) match {
    case Right(result) => result
    case Left(error) => throw new MalformedParametersException(error)
  }
  val endpoint = s"/regions/${parameters.region}/pointcode?columnToReturn=${parameters.primaryKey.getOrElse(defaultRegionPrimaryKey)}"
}

case class RegionCodeStringColumnInfo(strategy: ComputationStrategyInfo, targetColId: ColumnId, userTargetColId: UserColumnId) extends AbstractRegionCodeColumnInfo {
  val parameters = JsonDecode[GeoRegionMatchOnStringParameterSchema[ResourceName, UserColumnId]].decode(strategy.parameters) match {
    case Right(result) => result
    case Left(error) => throw new MalformedParametersException(error)
  }
  val endpoint = s"/regions/${parameters.region}/stringcode?columnToMatch=${parameters.column}&columnToReturn=${parameters.primaryKey.getOrElse(defaultRegionPrimaryKey)}"
}

case class RegionCodeRowInfo(data: Row[SoQLValue], targetColId: ColumnId, userTargetColId: UserColumnId, endpoint: String)
abstract class AbstractRegionCodingHandler extends ComputationHandler[SoQLType, SoQLValue] {
  override type PerDatasetData = CookieSchema
  override type PerColumnData <: AbstractRegionCodeColumnInfo
  override type PerCellData = RegionCodeRowInfo

  implicit class MapFuncs[K, V](underlying: Map[K, V]) {
    def mapValuesStrictly[V2](f: V => V2): Map[K, V2] =
      Map.empty[K, V2] ++ underlying.mapValues(f)
  }

  override def setupDataset(cookie: CookieSchema) = cookie

  override def setupCell(colInfo: PerColumnData, row: Row[SoQLValue]): RegionCodeRowInfo = {
    RegionCodeRowInfo(row, colInfo.targetColId, colInfo.userTargetColId, colInfo.endpoint)
  }

  override def compute[RowHandle](sources: Map[RowHandle, Seq[PerCellData]]): Map[RowHandle, Map[UserColumnId, SoQLValue]] = {
    // ok, we'll need to partition `sources` into sub-maps grouped by the RCIs' endpoints.
    val splitSources: Map[String, Map[RowHandle, Seq[PerCellData]]] =
      sources.iterator.flatMap {
        case (rh, rcis) => rcis.iterator.map((rh, _))
      }.toSeq.groupBy(_._2.endpoint).mapValuesStrictly(_.groupBy(_._1).mapValuesStrictly(_.map(_._2)))

    splitSources.toSeq.par.map { case (endpoint, jobsForEndpoint) =>
      computeOneEndpoint(endpoint, jobsForEndpoint)
    }.fold(Map.empty[RowHandle, Map[UserColumnId, SoQLValue]])(mergeWith(_, _)(_ ++ _))
  }

  def computeOneEndpoint[RowHandle](endpoint: String, jobs: Map[RowHandle, Seq[PerCellData]]): Map[RowHandle, Map[UserColumnId, SoQLValue]] = {
    val allCells = JArray(jobs.values.flatten.map { pcd =>
      jsonify(pcd.data(pcd.targetColId))
    }.toSeq)
    val featureIds = regionCode(endpoint, allCells)
    assert(featureIds.length == allCells.length, "Region coder returned wrong number of results?")
    jobs.foldLeft((Map.empty[RowHandle, Map[UserColumnId, SoQLValue]], featureIds)) { (accRemaining, handlePCDs) =>
      val (acc, remaining) = accRemaining
      val (handle, pcds) = handlePCDs
      val (featuresHere, featuresLeftover) = remaining.splitAt(pcds.length)
      val row =
        (pcds, featuresHere).zipped.map { (pcd, featureId) =>
          val featureIdAsSoQLValue = featureId match {
            case Some(i) => SoQLNumber(new java.math.BigDecimal(i))
            case None => SoQLNull
          }
          pcd.userTargetColId -> featureIdAsSoQLValue
        }.toMap
      (acc + (handle -> row), featuresLeftover)
    }._1
  }

  def regionCode(endpoint: String, allCells: JArray): Seq[Option[Int]] = ???

  def jsonify(cv: SoQLValue): JValue = cv match {
    case SoQLText(s) =>
      JString(s)
    case SoQLPoint(p) =>
      val coord = p.getCoordinate
      JArray(Seq(coord.x, coord.y, coord.z).filterNot(_.isNaN).map(JNumber(_)))
    case _ =>
      JNull
  }

  def mergeWith[A, B](xs: Map[A, B], ys: Map[A, B])(f: (B, B) => B): Map[A, B] = {
    ys.foldLeft(xs) { (combined, yab) =>
      val (a,yb) = yab
      val newB = combined.get(a) match {
        case None => yb
        case Some(xb) => f(xb, yb)
      }
      combined.updated(a, newB)
    }
  }
}

class RegionCodingPointHandler extends AbstractRegionCodingHandler {
  override type PerColumnData = RegionCodePointColumnInfo

  override def matchesStrategyType(typ: StrategyType): Boolean =
    Set(ST.GeoRegion.name, ST.GeoRegionMatchOnPoint.name).contains(typ.underlying)

  override def setupColumn(cookie: CookieSchema, strategy: ComputationStrategyInfo, targetColId: UserColumnId): RegionCodePointColumnInfo = {
    new RegionCodePointColumnInfo(strategy, cookie.columnIdMap(targetColId), targetColId)
  }
}

class RegionCodingStringHandler extends AbstractRegionCodingHandler {
  override type PerColumnData = RegionCodeStringColumnInfo

  override def matchesStrategyType(typ: StrategyType): Boolean =
    ST.GeoRegionMatchOnPoint.name == typ.underlying

  override def setupColumn(cookie: CookieSchema, strategy: ComputationStrategyInfo, targetColId: UserColumnId): RegionCodeStringColumnInfo = {
    RegionCodeStringColumnInfo(strategy, cookie.columnIdMap(targetColId), targetColId)
  }
}
