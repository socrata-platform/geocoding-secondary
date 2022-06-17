resolvers ++= Seq(
    Resolver.url("socrata ivy", url("https://repo.socrata.com/artifactory/ivy-libs-release"))(Resolver.ivyStylePatterns)
)

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "1.1.0")

addSbtPlugin("com.github.sbt" % "sbt-release" % "1.1.0")
