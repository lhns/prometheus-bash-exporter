package de.lolhens.prometheus.bashexporter

import cats.effect._
import ch.qos.logback.classic.{Level, Logger}
import com.comcast.ip4s.{Host, Port}
import org.http4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.Server
import org.http4s.server.middleware.ErrorAction
import org.log4s.getLogger

object Main extends IOApp {
  private val logger = getLogger

  private def setLogLevel(level: Level): Unit = {
    val rootLogger = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger]
    rootLogger.setLevel(level)
  }

  override def run(args: List[String]): IO[ExitCode] = IO.defer {
    val config = Config.fromEnv
    println(config.summary + "\n")
    setLogLevel(config.logLevel)
    applicationResource(config).use(_ => IO.never)
  }

  def applicationResource(config: Config): Resource[IO, Unit] =
    for {
      _ <- serverResource(
        config.host,
        config.port,
        new ScriptRoutes(config.scripts).toRoutes.orNotFound
      )
    } yield ()

  def serverResource(host: Host, port: Port, http: HttpApp[IO]): Resource[IO, Server] =
    EmberServerBuilder.default[IO]
      .withHost(host)
      .withPort(port)
      .withHttpApp(
        ErrorAction.log(
          http = http,
          messageFailureLogAction = (t, msg) => IO(logger.debug(t)(msg)),
          serviceErrorLogAction = (t, msg) => IO(logger.error(t)(msg))
        ))
      .build
}
