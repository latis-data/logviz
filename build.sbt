ThisBuild / organization := "io.latis-data"
ThisBuild / scalaVersion := "3.3.6"

val catsVersion = "2.13.0"
val catsEffectVersion = "3.6.1"
val fs2Version = "3.12.0"
val http4sVersion = "0.23.30"
val log4catsVersion = "2.7.1"

val commonSettings = Seq(
  scalacOptions -= "-Xfatal-warnings",
  scalacOptions += {
    if (insideCI.value) "-Wconf:any:e" else "-Wconf:any:w"
  }
)

lazy val root = project
  .in(file("."))
  .aggregate(
    backend,
    frontend,
    shared.js,
    shared.jvm
  )

lazy val app = project
  .in(file("modules/app"))
  .dependsOn(backend)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4catsVersion,
      "ch.qos.logback" % "logback-classic" % "1.5.18" % Runtime
    )
  )

lazy val backend = project
  .in(file("modules/backend"))
  .dependsOn(shared.jvm)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "org.http4s" %% "http4s-core" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4catsVersion
    )
  )

lazy val frontend = project
  .in(file("modules/frontend"))
  .dependsOn(shared.js)
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .settings(
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "com.armanbilge" %%% "calico" % "0.2.3",
      "org.typelevel" %%% "cats-core" % catsVersion,
      "org.typelevel" %%% "cats-effect" % catsEffectVersion,
      "co.fs2" %%% "fs2-core" % fs2Version,
      "com.armanbilge" %%% "fs2-dom" % "0.2.1",
      "org.http4s" %%% "http4s-circe" % http4sVersion,
      "org.http4s" %%% "http4s-client" % http4sVersion,
      "org.http4s" %%% "http4s-dom" % "0.2.12"
    )
  )

lazy val shared = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/shared"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core" % "0.14.13"
    )
  )

lazy val devCompile = taskKey[Unit](
  "Compile the frontend for dev and copy to the backend resource directory"
)

devCompile := {
  val js = (frontend / Compile / fastLinkJSOutput).value
  val dst = (backend / Compile / resourceDirectory).value
  IO.copyFile(js / "main.js", dst / "main.js")
}

lazy val prodCompile = taskKey[Unit](
  "Compile the frontend for prod and copy to the backend resource directory"
)

prodCompile := {
  val js = (frontend / Compile / fullLinkJSOutput).value
  val dst = (backend / Compile / resourceDirectory).value
  IO.copyFile(js / "main.js", dst / "main.js")
}

addCommandAlias("runDev", "; devCompile; app/reStart")
addCommandAlias("runProd", "; prodCompile; app/reStart")
