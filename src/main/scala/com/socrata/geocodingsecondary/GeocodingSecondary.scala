package com.socrata.geocodingsecondary

import com.rojoma.simplearm.v2.Resource
import com.socrata.datacoordinator.secondary.feedback.ComputationHandler
import com.socrata.datacoordinator.secondary.feedback.instance.FeedbackSecondaryInstance
import com.socrata.geocoders._
import com.socrata.geocoders.caching.{NoopCacheClient, PostgresqlCacheClient}
import com.socrata.geocoders.config.CacheConfig
import com.socrata.geocodingsecondary.config.GeocodingSecondaryConfig
import com.socrata.soql.types.{SoQLValue, SoQLType}
import com.typesafe.config.{ConfigFactory, Config}
import org.apache.curator.x.discovery.strategies

class GeocodingSecondary(config: GeocodingSecondaryConfig) extends FeedbackSecondaryInstance(config) {
  // SecondaryWatcher will give me a config, but just in case fallback to config from my jar file
  def this(rawConfig: Config) = this(new GeocodingSecondaryConfig(rawConfig.withFallback(
    ConfigFactory.load(classOf[GeocodingSecondary].getClassLoader).getConfig("com.socrata.geocoding-secondary"))))

  val geocoderProvider: OptionalGeocoder = locally {
    val geoConfig = config.geocoder

    val cacheClient =
      geoConfig.cache.map { cc =>
        cc.preference match {
          case Some(CacheConfig.Postgresql) =>
            config.postgresql match {
              case Some(pg) =>
                val dataSource = res(PostgresqlFromConfig.unmanaged(pg))
                new PostgresqlCacheClient(dataSource, cc.ttl)
              case None =>
                throw new Exception("Postgresql cache requested but no postgresql configured")
            }
          case Some(CacheConfig.None) =>
            NoopCacheClient
          case None =>
            throw new Exception("Must porvide a geocoding cache preference")
        }
      }.getOrElse {
        log.warn("No cache config provided; using {}.", NoopCacheClient.getClass)
        NoopCacheClient
      }

    val baseProvider: BaseGeocoder = geoConfig.mapQuest match {
      case Some(e) => new MapQuestGeocoder(httpClient, e.appToken, { (_, _) => }) // retry count defaults to 5
      case None => log.warn("No MapQuest config provided; using {}.", BaseNoopGeocoder.getClass); BaseNoopGeocoder
    }

    val provider: Geocoder =
      new CachingGeocoderAdapter(cacheClient, baseProvider, { _ => }, geoConfig.filterMultipier)

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
      case None => throw new Exception("Cannot find instance of " + config.regioncoder.service + " with which to region-code") // TODO: really?
    }

  override val computationHandlers: Seq[ComputationHandler[SoQLType, SoQLValue]] =
    List(new GeocodingHandler(geocoderProvider),
         new RegionCodingPointHandler(httpClient, regionCoderURL _, config.regioncoder.connectTimeout, config.regioncoder.readTimeout, config.regioncoder.retries),
         new RegionCodingStringHandler(httpClient, regionCoderURL _, config.regioncoder.connectTimeout, config.regioncoder.readTimeout, config.regioncoder.retries))

}
