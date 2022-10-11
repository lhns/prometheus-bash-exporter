package de.lhns.prometheus.bashexporter

import ch.qos.logback.classic.Level
import com.comcast.ip4s._

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

case class Config(logLevel: Level,
                  host: Host,
                  port: Port,
                  scripts: Seq[Script],
                 ) {
  def summary: String = {
    s"""LOG_LEVEL: $logLevel
       |SERVER_HOST: $host
       |SERVER_PORT: $port
       |${
      scripts.map { script =>
        (script, script.scriptLines.map("\n  " + _).mkString)
      }.sortBy(_._1.variableName).map {
        case (script, scriptLines) =>
          s"${script.variableName}${script.cacheTtl.map(ttl => s" (cache for $ttl)").getOrElse("")}:$scriptLines"
      }.mkString("\n")
    }""".stripMargin
  }
}

object Config {
  val default: Config = Config(
    logLevel = Level.ERROR,
    host = host"0.0.0.0",
    port = port"8080",
    scripts = Seq.empty
  )

  lazy val fromEnv: Config = {
    val env: Map[String, String] = System.getenv().asScala.toMap.map(e => (e._1, e._2.trim)).filter(_._2.nonEmpty)

    val logLevel: Level = env.get("LOG_LEVEL").map(Level.valueOf).getOrElse(default.logLevel)
    val host: Host = env.get("SERVER_HOST").map(Host.fromString(_).get).getOrElse(default.host)
    val port: Port = env.get("SERVER_PORT").map(Port.fromString(_).get).getOrElse(default.port)
    val cacheTtl: Map[Seq[String], FiniteDuration] = env.iterator.collect {
      case Script.RouteCache(route, ttl) => (route, ttl)
    }.toMap
    val scripts: Seq[Script] = env.iterator.collect {
      case Script.RouteScript(route, scriptLines) => Script(route, scriptLines, cacheTtl.get(route))
    }.toSeq.sortBy(_.route.size).reverse
    require(scripts.nonEmpty, "Environment variable SCRIPT must not be empty!")

    Config(
      logLevel = logLevel,
      host = host,
      port = port,
      scripts = scripts
    )
  }
}
