version            := "0.1.0"
scalaVersion       := "3.2.2"
run / fork         := true
Test / logBuffered := false

resolvers += "jitpack" at "https://jitpack.io"

scalacOptions ++= Seq("-language:implicitConversions", "-deprecation")

lazy val scalax = (project in file("."))
  .settings(
    name := "$"
  )

libraryDependencies += "org.log4s"     %% "log4s"           % "1.10.0"
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.4.5"
libraryDependencies += "com.fasterxml.jackson.module"    %% "jackson-module-scala"   % "2.14.0"
libraryDependencies += "com.fasterxml.jackson.dataformat" % "jackson-dataformat-csv" % "2.14.0"
libraryDependencies += "org.apache.commons" % "commons-lang3" % "3.12.0"
libraryDependencies += "org.apache.commons" % "commons-collections4" % "4.4"
libraryDependencies += "com.squareup.okhttp3" % "okhttp" % "4.10.0"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % "2.7.0",
  "com.typesafe.akka" %% "akka-stream" % "2.7.0",
  "com.typesafe.akka" %% "akka-http" % "10.5.0-M1"
).map(_ % "provided")

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.13" % Test
