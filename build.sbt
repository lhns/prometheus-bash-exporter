name := "prometheus-bash-exporter"
version := {
  val Tag = "refs/tags/(.*)".r
  sys.env.get("CI_VERSION").collect { case Tag(tag) => tag }
    .getOrElse("0.0.1-SNAPSHOT")
}

scalaVersion := "2.13.8"

val http4sVersion = "0.23.12"

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % "1.2.11",
  "io.github.vigoo" %% "prox-fs2-3" % "0.7.7",
  "org.graalvm.nativeimage" % "svm" % "22.0.0.2" % Provided,
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-blaze-server" % http4sVersion,
  "org.typelevel" %% "cats-effect" % "3.3.12",
)

addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")

Compile / doc / sources := Seq.empty

assembly / assemblyJarName := s"${name.value}-${version.value}.sh.bat"

assembly / assemblyOption := (assembly / assemblyOption).value
  .withPrependShellScript(Some(AssemblyPlugin.defaultUniversalScript(shebang = false)))

assembly / assemblyMergeStrategy := {
  case PathList(paths@_*) if paths.last == "module-info.class" => MergeStrategy.discard
  case PathList("META-INF", "jpms.args") => MergeStrategy.discard
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

enablePlugins(
  GraalVMNativeImagePlugin
)

GraalVMNativeImage / name := (GraalVMNativeImage / name).value + "-" + (GraalVMNativeImage / version).value
graalVMNativeImageOptions ++= Seq(
  //"--static",
  "--no-server",
  "--no-fallback",
  "--initialize-at-build-time",
  "--install-exit-handlers",
  "--enable-url-protocols=http,https",
  "--allow-incomplete-classpath" /*logback-classic*/
)
