import xerial.sbt.Sonatype.sonatypeCentralHost

ThisBuild / licenses               := Seq("ISC" -> url("https://opensource.org/licenses/ISC"))
ThisBuild / versionScheme          := Some("semver-spec")
ThisBuild / evictionErrorLevel     := Level.Warn
ThisBuild / scalaVersion           := "3.8.3"
ThisBuild / organization           := "io.github.edadma"
ThisBuild / organizationName       := "edadma"
ThisBuild / organizationHomepage   := Some(url("https://github.com/edadma"))
ThisBuild / version                := "0.5.3"
ThisBuild / description            := "A lightweight cross-platform HTTP server for Scala (JVM/JS/Native)"
ThisBuild / sonatypeCredentialHost := sonatypeCentralHost

ThisBuild / publishConfiguration := publishConfiguration.value.withOverwrite(true).withChecksums(Vector.empty)
ThisBuild / resolvers += Resolver.mavenLocal
ThisBuild / resolvers += Resolver.sonatypeCentralSnapshots
ThisBuild / resolvers += Resolver.sonatypeCentralRepo("releases")

ThisBuild / sonatypeProfileName := "io.github.edadma"

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/edadma/microserve"),
    "scm:git@github.com:edadma/microserve.git",
  ),
)
ThisBuild / developers := List(
  Developer(
    id = "edadma",
    name = "Edward A. Maxedon, Sr.",
    email = "edadma@gmail.com",
    url = url("https://github.com/edadma"),
  ),
)

ThisBuild / homepage := Some(url("https://github.com/edadma/microserve"))

ThisBuild / publishTo := sonatypePublishToBundle.value

lazy val microserve = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("."))
  .settings(
    name := "microserve",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-language:implicitConversions",
    ),
    libraryDependencies += "org.scalatest" %%% "scalatest" % "3.2.19" % Test,
    publishMavenStyle      := true,
    Test / publishArtifact := false,
  )
  .jvmSettings(
    // JVM uses java.nio internally — no extra deps.
  )
  .jsSettings(
    jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(),
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
    Test / scalaJSUseMainModuleInitializer := false,
    Test / scalaJSUseTestModuleInitializer := true,
    scalaJSUseMainModuleInitializer        := false,
  )
  .nativeSettings(
    libraryDependencies ++= Seq(
      "io.github.edadma" %%% "libuv" % "0.0.33",
      "io.github.edadma" %%% "async" % "0.0.18",
    ),
  )

lazy val root = project
  .in(file("."))
  .aggregate(microserve.jvm, microserve.js, microserve.native)
  .settings(
    name                := "microserve",
    publish / skip      := true,
    publishLocal / skip := true,
  )
