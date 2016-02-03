package com.socrata.geocodingsecondary

import com.netflix.astyanax.AstyanaxContext
import com.rojoma.simplearm.v2.Resource
import com.socrata.datacoordinator.secondary.feedback.ComputationHandler
import com.socrata.datacoordinator.secondary.feedback.instance.FeedbackSecondaryInstance
import com.socrata.geocoders._
import com.socrata.geocoders.caching.{NoopCacheClient, CassandraCacheClient}
import com.socrata.geocodingsecondary.config.GeocodingSecondaryConfig
import com.socrata.soql.types.{SoQLValue, SoQLType}
import com.socrata.thirdparty.astyanax.AstyanaxFromConfig
import com.typesafe.config.{ConfigFactory, Config}

class GeocodingSecondary(config: GeocodingSecondaryConfig) extends FeedbackSecondaryInstance[GeocodeRowInfo](config) {
  // SecondaryWatcher will give me a config, but just in case fallback to config from my jar file
  def this(rawConfig: Config) = this(new GeocodingSecondaryConfig(rawConfig.withFallback(
    ConfigFactory.load(classOf[GeocodingSecondary].getClassLoader).getConfig("com.socrata.geocoding-secondary"))))

  implicit def astyanaxResource[T] = new Resource[AstyanaxContext[T]] {
    def close(k: AstyanaxContext[T]) = k.shutdown()
  }
  val keyspace = res(AstyanaxFromConfig.unmanaged(config.cassandra))
  guarded(keyspace.start())

  val geocoderProvider = locally {
    val geoConfig = config.geocoder

    def baseProvider = geoConfig.mapQuest match {
      case Some(e) => new MapQuestGeocoder(httpClient, e.appToken, { (_, _) => }) // retry count defaults to 5
      case None => NoopGeocoder
    }

    def provider: Geocoder = {
      val cache = geoConfig.cache match {
        case Some(cacheConfig) =>
          new CassandraCacheClient(keyspace.getClient, cacheConfig.columnFamily, cacheConfig.ttl)
        case None =>
          NoopCacheClient
      }
      geoConfig.cache.fold(baseProvider) { cacheConfig =>
        new CachingGeocoderAdapter(cache, baseProvider, { _ => }, geoConfig.filterMultipier)
      }
    }

    provider
  }

  override val computationHandler: ComputationHandler[SoQLType, SoQLValue, GeocodeRowInfo] =
    new GeocodingHandler(geocoderProvider, config.computationRetries)

}
