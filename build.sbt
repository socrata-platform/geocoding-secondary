organization := "com.socrata"
scalaVersion := "2.12.8"

scalacOptions ++= Seq("-deprecation", "-feature", "-Xfatal-warnings")

resolvers += "socrata" at "https://repo.socrata.com/artifactory/libs-release"

name := "secondary-watcher-geocoding"

libraryDependencies ++= Seq(
  "com.mchange" % "c3p0" % "0.9.5.5",
  "org.postgresql" % "postgresql" % "42.2.12",
  "com.rojoma" %% "rojoma-json-v3" % "3.10.0",
  "com.socrata" %% "computation-strategies" % "0.1.3",
  "com.socrata" %% "geocoders" % "3.1.3",
  "com.socrata" %% "secondarylib-feedback" % "3.8.11",
  "javax.servlet" % "javax.servlet-api" % "3.1.0", // needed for socrata-http-server
  "com.socrata" %% "socrata-http-server" % "3.13.3", // we are just using RequestId from this
  "com.socrata" %% "socrata-curator-utils" % "1.2.0",
  "com.socrata" %% "socrata-thirdparty-utils" % "5.0.0",
  "com.socrata" %% "soql-types" % "2.16.1",
  "com.typesafe" % "config" % "1.2.0",
  "org.scalatest" %% "scalatest" % "3.0.8" % "test"
)

test in assembly := {}

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", "io.netty.versions.properties") =>
    MergeStrategy.first
  case PathList("module-info.class") =>
    MergeStrategy.discard
  case other =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(other)
}
