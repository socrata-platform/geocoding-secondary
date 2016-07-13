package com.socrata.geocodingsecondary

import com.socrata.datacoordinator.secondary.SecondaryWatcherApp

object Main extends App {
  val instance = Option(System.getenv("SECONDARY_INSTANCE_NAME")).getOrElse {
    System.err.println("SECONDARY_INSTANCE_NAME environment variable not set")
    sys.exit(1)
  }
  SecondaryWatcherApp(instance, new GeocodingSecondary(_))
}
