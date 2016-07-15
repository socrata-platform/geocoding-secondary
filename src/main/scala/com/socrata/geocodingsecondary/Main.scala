package com.socrata.geocodingsecondary

import com.socrata.datacoordinator.secondary.SecondaryWatcherApp

object Main extends App {
  val instance = Option(System.getenv("GEOCODING_SECONDARY_INSTANCE")).getOrElse {
    System.err.println("GEOCODING_SECONDARY_INSTANCE environment variable not set")
    sys.exit(1)
  }
  SecondaryWatcherApp(instance, new GeocodingSecondary(_))
}
