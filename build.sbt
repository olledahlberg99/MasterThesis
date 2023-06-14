ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.12.17"
ThisBuild / libraryDependencies ++= Seq( "org.apache.spark" %% "spark-core" % "3.3.2")
ThisBuild / libraryDependencies += "org.apache.spark" %% "spark-graphx" % "3.3.2"
ThisBuild / libraryDependencies += "org.apache.spark" %% "spark-sql" % "3.3.2"
//ThisBuild / libraryDependencies += "org.apache.spark" %% "spark-repl" % "3.3.2"
ThisBuild / libraryDependencies += "org.apache.spark" %% "spark-mllib" % "3.3.2"
scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-feature",
  "-unchecked"
)
lazy val root = (project in file("."))
  .settings(
    name := "PregelRepeat"
  )