package com.socrata.geocodingsecondary.config

import com.socrata.datacoordinator.secondary.feedback.instance.config.FeedbackSecondaryInstanceConfig
import com.socrata.geocoders.config.{CacheConfig, GeocoderConfig}
import com.socrata.thirdparty.typesafeconfig.{ConfigClass, C3P0Propertizer}
import com.typesafe.config.{ConfigFactory, Config}

class GeocodingSecondaryConfig(config: Config = ConfigFactory.load().getConfig("com.socrata.geocoding-secondary")) extends FeedbackSecondaryInstanceConfig(config, "") {
  val postgresql = optionally(getRawConfig("postgresql")).map { _ =>
    getConfig("postgresql", new PostgresqlConfig(_, _))
  }
  val geocoder = getConfig("geocoder", new GeocodingConfig(_, _))
  val regioncoder = getConfig("regioncoder", new RegionCoderConfig(_, _))
}

class PostgresqlConfig(config: Config, root: String) extends ConfigClass(config, root) {
  val host = getString("host")
  val port = getInt("port")
  val database = getString("database")
  val username = getString("user")
  val password = getString("password")
  val applicationName = getString("app-name")
  val tcpKeepAlive = optionally(getBoolean("tcp-keep-alive")).getOrElse(false)
  val poolOptions = optionally(getRawConfig("c3p0")).map { _ =>
    getConfig("c3p0", (s, c) => C3P0Propertizer(c, s))
  }
}

class GeocodingConfig(config: Config, root: String) extends ConfigClass(config, root) {
  val filterMultipier = getInt("filter-multiplier")
  val cache = optionally(getRawConfig("cache")) map { _ =>
    getConfig("cache", new CacheConfig(_, _))
  }

  val geocoder = optionally(getRawConfig("geocoder")) map { _ =>
    getConfig("geocoder", GeocoderConfig(_, _))
  }
}

class RegionCoderConfig(config: Config, root: String) extends ConfigClass(config, root) {
  val service = getString("service")
  val connectTimeout = getDuration("connect-timeout")
  val readTimeout = getDuration("read-timeout")
  val retries = getInt("retries")
}
