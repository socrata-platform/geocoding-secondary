package com.socrata.geocodingsecondary

import com.netflix.astyanax.AstyanaxContext
import com.rojoma.simplearm.v2.Resource
import com.socrata.datacoordinator.secondary.feedback.{ComputationFailure, ComputationHandler}
import com.socrata.datacoordinator.secondary.feedback.instance.FeedbackSecondaryInstance
import com.socrata.geocoders._
import com.socrata.geocoders.caching.{NoopCacheClient, CassandraCacheClient}
import com.socrata.geocodingsecondary.config.GeocodingSecondaryConfig
import com.socrata.soql.types.{SoQLValue, SoQLType}
import com.socrata.thirdparty.astyanax.AstyanaxFromConfig
import com.typesafe.config.{ConfigFactory, Config}
import org.apache.curator.x.discovery.strategies

class GeocodingSecondary(config: GeocodingSecondaryConfig) extends FeedbackSecondaryInstance(config) {
  // SecondaryWatcher will give me a config, but just in case fallback to config from my jar file
  def this(rawConfig: Config) = this(new GeocodingSecondaryConfig(rawConfig.withFallback(
    ConfigFactory.load(classOf[GeocodingSecondary].getClassLoader).getConfig("com.socrata.geocoding-secondary"))))

  implicit def astyanaxResource[T] = new Resource[AstyanaxContext[T]] {
    def close(k: AstyanaxContext[T]) = k.shutdown()
  }
  val keyspace = res(AstyanaxFromConfig.unmanaged(config.cassandra))
  guarded(keyspace.start())

  val geocoderProvider: OptionalGeocoder = locally {
    val geoConfig = config.geocoder

    def baseProvider: BaseGeocoder = geoConfig.mapQuest match {
      case Some(e) => new MapQuestGeocoder(httpClient, e.appToken, { (_, _) => }) // retry count defaults to 5
      case None => log.warn("No MapQuest config provided; using {}.", BaseNoopGeocoder.getClass); BaseNoopGeocoder
    }

    def provider: Geocoder = {
      val cache = geoConfig.cache match {
        case Some(cacheConfig) =>
          new CassandraCacheClient(keyspace.getClient, cacheConfig.columnFamily, cacheConfig.ttl)
        case None =>
          log.warn("No cache config provided; using {}.", NoopCacheClient.getClass)
          NoopCacheClient
      }
      new CachingGeocoderAdapter(cache, baseProvider, { _ => }, geoConfig.filterMultipier)
    }

    new OptionRemoverGeocoder(provider, multiplier = 1 /* we don't want to batch filtering out Nones */)
  }

  override val computationRetryLimit = config.computationRetries
  override val user = "geocoding-secondary"

  val regionDiscoveryProvider =
    res(discovery.serviceProviderBuilder().
      providerStrategy(new strategies.RoundRobinStrategy).
      serviceName(config.regioncoder.service).
      build())
  regionDiscoveryProvider.start()

  def regionCoderURL() =
    Option(regionDiscoveryProvider.getInstance()) match {
      case Some(spec) => spec.buildUriSpec()
      case None => throw ComputationFailure("Cannot find instance of " + config.regioncoder.service + " with which to region-code")
    }

  override val computationHandlers: Seq[ComputationHandler[SoQLType, SoQLValue]] =
    List(new GeocodingHandler(geocoderProvider),
         new RegionCodingPointHandler(httpClient, regionCoderURL, config.regioncoder.connectTimeout, config.regioncoder.readTimeout, config.regioncoder.retries),
         new RegionCodingStringHandler(httpClient, regionCoderURL, config.regioncoder.connectTimeout, config.regioncoder.readTimeout, config.regioncoder.retries))

}
