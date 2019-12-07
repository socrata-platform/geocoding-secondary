resolvers ++= Seq(
    Resolver.url("socrata ivy", url("https://repo.socrata.com/artifactory/ivy-libs-release"))(Resolver.ivyStylePatterns)
)

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.10")
