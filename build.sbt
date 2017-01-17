name := "wallet-proto"

version := "0.1-SNAPSHOT"

scalaVersion := "2.11.8"

libraryDependencies ++= {

  val akkaVersion = "2.4.16"
  val tests = "test"

  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-query-experimental" % akkaVersion,
    "org.iq80.leveldb"            % "leveldb"          % "0.7",
    "org.fusesource.leveldbjni"   % "leveldbjni-all"   % "1.8",
    // "net.cakesolutions" %% "scala-kafka-client" % "0.10.1.1"
    "com.typesafe.akka" %% "akka-stream-kafka" % "0.13",
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "org.scalatest" %% "scalatest" % "3.0.0" % tests,
    "org.scalamock" %% "scalamock-scalatest-support" % "3.4.2" % tests,
    "net.manub" %% "scalatest-embedded-kafka" % "0.11.0" % tests,
    "com.typesafe.akka" %% "akka-stream-testkit-experimental" % "2.0.5"
  )
}

resolvers ++= Seq(
  // Resolver.bintrayRepo("cakesolutions", "maven")
)

parallelExecution in Test := false

// wartremoverErrors ++= Warts.all //TODO: enable
