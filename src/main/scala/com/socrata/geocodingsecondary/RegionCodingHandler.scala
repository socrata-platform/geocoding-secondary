package com.socrata.geocodingsecondary

import java.io.IOException

import com.rojoma.json.v3.ast._
import com.rojoma.json.v3.codec.JsonDecode.DecodeResult
import com.rojoma.json.v3.codec.{JsonEncode, JsonDecode}
import com.rojoma.json.v3.io.{JsonReaderException, JValueEventIterator}
import com.rojoma.json.v3.util.WrapperJsonCodec
import com.socrata.datacoordinator.id.{ColumnId, StrategyType, UserColumnId}
import com.socrata.computation_strategies.{StrategyType => ST, GeoRegionMatchOnStringParameterSchema, GeoRegionMatchOnPointParameterSchema}
import com.socrata.datacoordinator.secondary.{ComputationStrategyInfo, Row}
import com.socrata.datacoordinator.secondary.feedback._
import com.socrata.http.client.exceptions.HttpClientException
import com.socrata.http.client.{RequestBuilder, HttpClient}
import com.socrata.http.server.util.RequestId

import com.socrata.soql.environment.ColumnName
import com.socrata.soql.types._
import org.slf4j.{MDC, LoggerFactory}

import scala.concurrent.duration.FiniteDuration

case class ResourceName(underlying: String) {
  override final def toString = underlying
}
object ResourceName extends (String => ResourceName) {
  implicit val jCodec = WrapperJsonCodec[ResourceName](this, _.underlying)
}

trait AbstractRegionCodeColumnInfo extends HasStrategy {
  implicit val columnNameCodec = WrapperJsonCodec[ColumnName](ColumnName, _.name)
  def defaultRegionPrimaryKey = ColumnName("_feature_id")

  def cookie: CookieSchema
  def userTargetColId: UserColumnId
  def endpoint: String

  final def sourceColId = cookie.columnIdMap(strategy.sourceColumnIds.head) // there will be exactly one
  final def targetColId = cookie.columnIdMap(userTargetColId)
}

case class RegionCodePointColumnInfo(cookie: CookieSchema, strategy: ComputationStrategyInfo, userTargetColId: UserColumnId) extends AbstractRegionCodeColumnInfo {
  val parameters = JsonDecode[GeoRegionMatchOnPointParameterSchema[ResourceName, ColumnName]].decode(strategy.parameters) match {
    case Right(result) => result
    case Left(error) => throw new MalformedParametersException(error)
  }
  val endpoint = s"/regions/${parameters.region}/pointcode?columnToReturn=${parameters.primaryKey.getOrElse(defaultRegionPrimaryKey)}"
}

case class RegionCodeStringColumnInfo(cookie: CookieSchema, strategy: ComputationStrategyInfo, userTargetColId: UserColumnId) extends AbstractRegionCodeColumnInfo {
  val parameters = JsonDecode[GeoRegionMatchOnStringParameterSchema[ResourceName, UserColumnId]].decode(strategy.parameters) match {
    case Right(result) => result
    case Left(error) => throw new MalformedParametersException(error)
  }
  val endpoint = s"/regions/${parameters.region}/stringcode?columnToMatch=${parameters.column}&columnToReturn=${parameters.primaryKey.getOrElse(defaultRegionPrimaryKey)}"
}

case class RegionCodeRowInfo(data: Row[SoQLValue], sourceColId: ColumnId, targetColId: ColumnId, userTargetColId: UserColumnId, endpoint: String)
abstract class AbstractRegionCodingHandler(http: HttpClient,
                                           baseURL: () => String,
                                           connectTimeout: FiniteDuration,
                                           readTimeout: FiniteDuration,
                                           retries: Int) extends ComputationHandler[SoQLType, SoQLValue] {
  val log = LoggerFactory.getLogger(classOf[AbstractRegionCodingHandler])

  override type PerDatasetData = CookieSchema
  override type PerColumnData <: AbstractRegionCodeColumnInfo
  override type PerCellData = RegionCodeRowInfo

  implicit class MapFuncs[K, V](underlying: Map[K, V]) {
    def mapValuesStrictly[V2](f: V => V2): Map[K, V2] =
      Map.empty[K, V2] ++ underlying.mapValues(f)
  }

  override def setupDataset(cookie: CookieSchema) = cookie

  override def setupCell(colInfo: PerColumnData, row: Row[SoQLValue]): RegionCodeRowInfo = {
    RegionCodeRowInfo(row, colInfo.sourceColId, colInfo.targetColId, colInfo.userTargetColId, colInfo.endpoint)
  }

  override def compute[RowHandle](sources: Map[RowHandle, Seq[PerCellData]]): Either[ComputationFailure, Map[RowHandle, Map[UserColumnId, SoQLValue]]] = {
    try {
      // ok, we'll need to partition `sources` into sub-maps grouped by the RCIs' endpoints.
      val splitSources: Map[String, Map[RowHandle, Seq[PerCellData]]] =
        sources.iterator.flatMap {
          case (rh, rcis) => rcis.iterator.map((rh, _))
        }.toSeq.groupBy(_._2.endpoint).mapValuesStrictly(_.groupBy(_._1).mapValuesStrictly(_.map(_._2)))

      // maintain the same MDC context map for our logging
      val parentContextMap = Option(MDC.getCopyOfContextMap) // can be null

      Right(splitSources.toSeq.par.map { case (endpoint, jobsForEndpoint) =>
        // set thread name
        val thread = Thread.currentThread()
        val name = thread.getName
        thread.setName(s"ThreadId:${thread.getId} parallel region-coding for ${parentContextMap.map(_.get("dataset-id")).getOrElse("UNKNOWN")}")

        // we are in a worker thread here because of the parallel call
        parentContextMap.foreach(MDC.setContextMap)

        val computed = try {
          computeOneEndpoint(endpoint, jobsForEndpoint)
        } finally {
          // reset thread name and clear MDC
          thread.setName(name)
          MDC.clear()
        }

        computed
      }.fold(Map.empty[RowHandle, Map[UserColumnId, SoQLValue]])(mergeWith(_, _)(_ ++ _)))
    } catch {
      case ComputationErrorException(reason, cause) => Left(ComputationError(reason, cause))
      case FatalComputationErrorException(reason, cause) => Left(FatalComputationError(reason, cause))
    }
  }

  def computeOneEndpoint[RowHandle](endpoint: String, jobs: Map[RowHandle, Seq[PerCellData]]): Map[RowHandle, Map[UserColumnId, SoQLValue]] = {
    val allCells =
      for {
        pcds <- jobs.values.toVector
        pcd <- pcds
      } yield jsonify(pcd.data.getOrElse(pcd.sourceColId, null))

    // Ok, it'd be convenient if region-coder would take and return null values, but it doesn't,
    // so we need to strip them out and then realign the results with the non-null inputs.
    val noNulls = allCells.iterator.zipWithIndex.filter(_._1 != JNull).toVector
    val featureIdsRaw =
      if(noNulls.nonEmpty) regionCode(endpoint, JArray(noNulls.map(_._1))) // region-coder doesn't want empty sequences either
      else Seq.empty
    assert(featureIdsRaw.length == noNulls.length, "Region coder returned wrong number of results?")
    val featureIdsMap = noNulls.iterator.map(_._2).zip(featureIdsRaw.iterator).toMap
    val featureIds = allCells.iterator.zipWithIndex.map {
      case (JNull, idx) =>
        None
      case (_, idx) =>
        featureIdsMap(idx)
    }.toVector
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

  def jsonify(cv: SoQLValue): JValue =
    cv match {
      case SoQLText(s) =>
        JString(s)
      case SoQLPoint(p) =>
        val coord = p.getCoordinate
        val numbers =
          if(coord.z.isNaN) Seq(coord.x, coord.y)
          else Seq(coord.x, coord.y, coord.z)
        JsonEncode.toJValue(numbers)
      case _ =>
        JNull
    }

  def urlPrefix = baseURL() + "v2"

  implicit def optionIntCodec =
    new JsonDecode[Option[Int]] {
      def decode(x: JValue): DecodeResult[Option[Int]] =
        Right(JsonDecode[Int].decode(x).right.toOption)
    }

  def regionCode(endpoint: String, allCells: JArray): Seq[Option[Int]] = {
    def loop(retriesLeft: Int): Seq[Option[Int]] = {
      try {
        val base =
          RequestBuilder(new java.net.URI(urlPrefix + endpoint)).
            connectTimeoutMS(connectTimeout.toMillis.toInt).
            receiveTimeoutMS(readTimeout.toMillis.toInt).
            addHeader((RequestId.ReqIdHeader, MDC.get("job-id")))
        for(resp <- http.execute(base.json(JValueEventIterator(allCells)))) {
          resp.resultCode match {
            case 200 =>
              try {
                resp.value[Seq[Option[Int]]]() match {
                  case Right(r) =>
                    return r
                  case Left(e) =>
                    log.warn("Region-coding response was not well-typed: {}", e.english)
                    // and retry
                }
              } catch {
                case e: JsonReaderException =>
                  log.warn("Malformed json received while region coding", e)
                  // and retry
              }
            case 404 =>
              log.warn("Received a 404 result code for region coding: {}", endpoint)
              // do not retry upon 404
              // this will immediately mark the dataset as broken, which is fine since retrying this won't help
              throw FatalComputationErrorException(s"Failed to region code $endpoint; received a 404 result code")
            case other =>
              log.warn("Non-200 result code from region coding: {}", other)
              // and retry
          }
        }
      } catch {
        case e: IOException =>
          log.warn("IO Exception while region coding", e)
          // and retry
        case e: HttpClientException =>
          log.warn("HTTP client exception while region coding", e)
          // and retry
      }
      if(retriesLeft == 0) throw ComputationErrorException("Ran out of retries while region coding")
      else loop(retriesLeft - 1)
    }
    loop(retries)
  }

  def mergeWith[A, B](xs: Map[A, B], ys: Map[A, B])(f: (B, B) => B): Map[A, B] =
    ys.foldLeft(xs) { (combined, yab) =>
      val (a,yb) = yab
      val newB = combined.get(a) match {
        case None => yb
        case Some(xb) => f(xb, yb)
      }
      combined.updated(a, newB)
    }
}

class RegionCodingPointHandler(http: HttpClient,
                               baseURL: () => String,
                               connectTimeout: FiniteDuration,
                               readTimeout: FiniteDuration,
                               retries: Int) extends AbstractRegionCodingHandler(http, baseURL, connectTimeout, readTimeout, retries) {
  override type PerColumnData = RegionCodePointColumnInfo

  override def matchesStrategyType(typ: StrategyType): Boolean =
    Set(ST.GeoRegion.name, ST.GeoRegionMatchOnPoint.name).contains(typ.underlying)

  override def setupColumn(cookie: CookieSchema, strategy: ComputationStrategyInfo, targetColId: UserColumnId): RegionCodePointColumnInfo = {
    new RegionCodePointColumnInfo(cookie, strategy, targetColId)
  }
}

class RegionCodingStringHandler(http: HttpClient,
                                baseURL: () => String,
                                connectTimeout: FiniteDuration,
                                readTimeout: FiniteDuration,
                                retries: Int) extends AbstractRegionCodingHandler(http, baseURL, connectTimeout, readTimeout, retries) {
  override type PerColumnData = RegionCodeStringColumnInfo

  override def matchesStrategyType(typ: StrategyType): Boolean =
    ST.GeoRegionMatchOnString.name == typ.underlying

  override def setupColumn(cookie: CookieSchema, strategy: ComputationStrategyInfo, targetColId: UserColumnId): RegionCodeStringColumnInfo = {
    RegionCodeStringColumnInfo(cookie, strategy, targetColId)
  }
}
