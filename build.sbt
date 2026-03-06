val scala3Version = "3.7.1"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "de.hpi"

// speedup during sbt run
ThisBuild / run / fork := true

ThisBuild / scalacOptions ++= Seq(
  "-Wunused:all"
)
ThisBuild / scalafixDependencies += "io.github.dedis" %% "scapegoat-scalafix" % "1.1.4"

resolvers += "Jitpack (required for roaringbitmap)" at "https://jitpack.io"

// unmanagedJars in Compile ++= Seq(
//   new java.io.File(

//     "./datasets/HyOD_modified.jar"
//   )
// ).classpath

// Use compatible Scala version
scalaVersion := scala3Version
lazy val root = project
  .in(file("."))
  .enablePlugins(ScalafixPlugin)
  .settings(
    name := "dynsbod",
    version := "1.0.0",
    scalaVersion := scala3Version,
    assembly / mainClass := Some("main"),
    assembly / assemblyMergeStrategy := {
      case "module-info.class" => MergeStrategy.first
      // https://github.com/sbt/sbt-assembly/issues/483 to make logback work
      case PathList("META-INF", xs @ _*) =>
        (xs map { _.toLowerCase }) match {
          case "services" :: xs =>
            MergeStrategy.filterDistinctLines
          case _ => MergeStrategy.discard
        }
      case x => MergeStrategy.singleOrError
    },
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % "0.14.10",
      "io.circe" %% "circe-generic" % "0.14.10",
      "io.circe" %% "circe-parser" % "0.14.10",
      "org.scalameta" %% "munit" % "1.0.0" % Test,
      "ch.qos.logback" % "logback-classic" % "1.5.18",
      "com.github.RoaringBitmap.RoaringBitmap" % "roaringbitmap" % "1.5.1",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4"
    )
  )
  .settings(
    inThisBuild(
      List(
        scalaVersion := scala3Version,
        semanticdbEnabled := true,
        semanticdbVersion := "4.9.9"
      )
    )
  )
