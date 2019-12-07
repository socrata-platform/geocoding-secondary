package com.socrata.geocodingsecondary

import scala.collection.JavaConverters._

import java.net.{InetSocketAddress, InetAddress}

import com.datastax.driver.core.{Session, Cluster}
import com.socrata.thirdparty.typesafeconfig.CassandraConfig
import com.rojoma.simplearm.v2._

object CassandraFromConfig {
  def apply(config: CassandraConfig) = managed {
    unmanaged(config)
  }.flatMap { cluster =>
    managed(cluster.connect(config.keyspace))
  }

  private def hackPort(n: Int): Int =
    n match {
      case 9160 => 9042
      case other => other
    }

  def unmanaged(config: CassandraConfig): Cluster = {
    val addresses = config.connectionPool.seeds.split(',').toSeq.map { seedString =>
      seedString.indexOf(':') match {
        case -1 =>
          new InetSocketAddress(seedString, hackPort(config.connectionPool.port))
        case n =>
          new InetSocketAddress(seedString.take(n), hackPort(seedString.drop(n+1).toInt))
      }
    }
    Cluster.builder.
      addContactPointsWithPorts(addresses.asJava).
      withPort(hackPort(config.connectionPool.port)).
      build()
  }
}
