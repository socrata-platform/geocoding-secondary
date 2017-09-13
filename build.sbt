import sbt._

def astyanaxExcludes(x: ModuleID) = x exclude ("commons-logging", "commons-logging") exclude ("org.mortbay.jetty", "servlet-api") exclude ("javax.servlet", "servlet-api")
val astyanaxVersion =  "1.56.48"
val astyanaxCassandra = astyanaxExcludes("com.netflix.astyanax" % "astyanax-cassandra" % astyanaxVersion)
val astyanaxThrift = astyanaxExcludes("com.netflix.astyanax" % "astyanax-thrift" % astyanaxVersion)

val rojomaJsonV3            = "com.rojoma"  %% "rojoma-json-v3"             % "3.5.0"

val computationStrategies   = "com.socrata" %% "computation-strategies"     % "0.0.4"

val geocoders               = "com.socrata" %% "geocoders"                  % "2.0.7"

val dataCoordinator         = "com.socrata" %% "secondarylib-feedback"      % "3.3.19"

val javaxServlet            = "javax.servlet" % "javax.servlet-api"         % "3.1.0" // needed for socrata-http-server

val socrataHttpServer       = "com.socrata" %% "socrata-http-server"        % "3.11.1"

val socrataCuratorUtils     = "com.socrata" %% "socrata-curator-utils"      % "1.0.3"

val socrataThirdPartyUtils  = "com.socrata" %% "socrata-thirdparty-utils"   % "4.0.12"

val socrataSoqlTypes        = "com.socrata" %% "soql-types"                 % "1.0.1" excludeAll(ExclusionRule(organization = "com.rojoma"))

val typesafeConfig          = "com.typesafe" % "config"                     % "1.2.0"

lazy val commonSettings = Seq(
  organization := "com.socrata",
  scalaVersion := "2.10.4"
)

lazy val root = (project in file(".")).
  settings(commonSettings: _*).
  settings(
    name := "secondary-watcher-geocoding",
    libraryDependencies ++= Seq(
      astyanaxCassandra,
      astyanaxThrift,
      rojomaJsonV3,
      computationStrategies,
      geocoders,
      dataCoordinator,
      javaxServlet,
      socrataCuratorUtils,
      socrataHttpServer,
      socrataSoqlTypes,
      socrataThirdPartyUtils,
      typesafeConfig
    ),
    com.socrata.sbtplugins.StylePlugin.StyleKeys.styleCheck in Test := {},
    com.socrata.sbtplugins.StylePlugin.StyleKeys.styleCheck in Compile := {},
    com.socrata.sbtplugins.findbugs.JavaFindBugsPlugin.JavaFindBugsKeys.findbugsFailOnError in Test := false,
    com.socrata.sbtplugins.findbugs.JavaFindBugsPlugin.JavaFindBugsKeys.findbugsFailOnError in Compile := false
  )
