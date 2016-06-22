package com.socrata.geocodingsecondary.config

import com.socrata.datacoordinator.secondary.feedback.instance.config.FeedbackSecondaryInstanceConfig
import com.socrata.geocoders.config.{CassandraCacheConfig, MapQuestConfig}
import com.socrata.thirdparty.typesafeconfig.{CassandraConfig, ConfigClass}
import com.typesafe.config.{ConfigFactory, Config}

class GeocodingSecondaryConfig(config: Config = ConfigFactory.load().getConfig("com.socrata.geocoding-secondary")) extends FeedbackSecondaryInstanceConfig(config, "") {
  val cassandra = getConfig("cassandra", new CassandraConfig(_, _))
  val geocoder = getConfig("geocoder", new GeocoderConfig(_, _))
  val regioncoder = getConfig("regioncoder", new RegionCoderConfig(_, _))
}

class GeocoderConfig(config: Config, root: String) extends ConfigClass(config, root) {
  val filterMultipier = getInt("filter-multiplier")
  val cache = optionally(getRawConfig("cache")) map { _ =>
    getConfig("cache", new CassandraCacheConfig(_, _))
  }

  val mapQuest = optionally(getRawConfig("mapquest")) map { _ =>
    getConfig("mapquest", new MapQuestConfig(_, _))
  }
}

class RegionCoderConfig(config: Config, root: String) extends ConfigClass(config, root) {
  val service = getString("service")
  val connectTimeout = getDuration("connect-timeout")
  val readTimeout = getDuration("read-timeout")
  val retries = getInt("retries")
}
