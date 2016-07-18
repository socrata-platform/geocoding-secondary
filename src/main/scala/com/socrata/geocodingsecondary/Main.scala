package com.socrata.geocodingsecondary

import com.socrata.datacoordinator.secondary.SecondaryWatcherApp

object Main extends App {
  SecondaryWatcherApp(new GeocodingSecondary(_))
}
