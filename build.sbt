name := "scalax"

scalaVersion       := "3.3.0"
run / fork         := true
Test / logBuffered := false

resolvers += "jitpack" at "https://jitpack.io"

scalacOptions ++= Seq("-language:implicitConversions", "-deprecation")


libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4"
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.4.5"
libraryDependencies += "com.fasterxml.jackson.module"    %% "jackson-module-scala"   % "2.14.0"
libraryDependencies += "com.fasterxml.jackson.dataformat" % "jackson-dataformat-csv" % "2.14.0"
libraryDependencies += "org.apache.commons" % "commons-lang3" % "3.12.0"
libraryDependencies += "org.apache.commons" % "commons-collections4" % "4.4"
libraryDependencies += "com.squareup.okhttp3" % "okhttp" % "4.10.0"

libraryDependencies += "org.redisson" % "redisson" % "3.17.1" % "provided"
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % "2.8.2",
  "com.typesafe.akka" %% "akka-stream" % "2.8.2",
  "com.typesafe.akka" %% "akka-http" % "10.5.2"
).map(_ % "provided")

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.13" % Test
