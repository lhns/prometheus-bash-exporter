ThisBuild / scalaVersion := "2.13.15"

val V = new {
  val catsEffect = "3.5.7"
  val http4s = "0.23.27"
  val logbackClassic = "1.5.12"
  val munit = "1.0.0"
  val munitTaglessFinal = "0.2.0"
  val nativeimage = "24.0.2"
  val prox = "0.8.0"
}

lazy val commonSettings: Seq[Setting[_]] = Seq(
  version := {
    val Tag = "refs/tags/v?([0-9]+(?:\\.[0-9]+)+(?:[+-].*)?)".r
    sys.env.get("CI_VERSION").collect { case Tag(tag) => tag }
      .getOrElse("0.0.1-SNAPSHOT")
  },
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
  libraryDependencies ++= Seq(
    "ch.qos.logback" % "logback-classic" % V.logbackClassic % Test,
    "de.lolhens" %% "munit-tagless-final" % V.munitTaglessFinal % Test,
    "org.scalameta" %% "munit" % V.munit % Test
  ),
  testFrameworks += new TestFramework("munit.Framework"),
  assembly / assemblyJarName := s"${name.value}-${version.value}.sh.bat",
  assembly / assemblyOption := (assembly / assemblyOption).value
    .withPrependShellScript(Some(AssemblyPlugin.defaultUniversalScript(shebang = false))),
  assembly / assemblyMergeStrategy := {
    case PathList(paths@_*) if paths.last == "module-info.class" => MergeStrategy.discard
    case x =>
      val oldStrategy = (assembly / assemblyMergeStrategy).value
      oldStrategy(x)
  }
)

lazy val root = (project in file("."))
  .enablePlugins(GraalVMNativeImagePlugin)
  .settings(commonSettings)
  .settings(
    name := "prometheus-bash-exporter",

    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % V.logbackClassic,
      "io.github.vigoo" %% "prox-fs2-3" % V.prox,
      "org.graalvm.nativeimage" % "svm" % V.nativeimage % Provided,
      "org.http4s" %% "http4s-dsl" % V.http4s,
      "org.http4s" %% "http4s-ember-server" % V.http4s,
      "org.typelevel" %% "cats-effect" % V.catsEffect,
    ),

    GraalVMNativeImage / name := (GraalVMNativeImage / name).value + "-" + (GraalVMNativeImage / version).value,
    graalVMNativeImageOptions ++= Seq(
      //"--static",
      "--no-server",
      "--no-fallback",
      "--initialize-at-build-time",
      "--install-exit-handlers",
      "--enable-url-protocols=http,https",
      "--allow-incomplete-classpath" /*logback-classic*/
    )
  )
