package de.lolhens.prometheus.bashexporter

import cats.effect.{Blocker, ExitCode, Resource}
import ch.qos.logback.classic.{Level, Logger}
import io.github.vigoo.prox.ProxFS2
import monix.eval.{Task, TaskApp}
import org.http4s._
import org.http4s.dsl.task._
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.AutoSlash

import scala.jdk.CollectionConverters._

object Main extends TaskApp {
  private def setLogLevel(level: Level): Unit = {
    val rootLogger = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger]
    rootLogger.setLevel(level)
  }

  override def run(args: List[String]): Task[ExitCode] = Task.defer {
    val options = Options.fromEnv
    println(options.debug + "\n")
    setLogLevel(options.logLevel)
    new Server(options).run
  }

  case class Options(logLevel: Level,
                     host: String,
                     port: Int,
                     script: String) {
    def debug: String = {
      s"""LOG_LEVEL: $logLevel
         |SERVER_HOST: $host
         |SERVER_PORT: $port
         |SCRIPT:${script.split("\\n").map("\n  " + _).mkString}""".stripMargin
    }
  }

  object Options {
    val default: Options = Options(
      logLevel = Level.INFO,
      host = "0.0.0.0",
      port = 8080,
      script = ""
    )

    def fromEnv: Options = {
      val env: Map[String, String] = System.getenv().asScala.toMap.map(e => (e._1, e._2.trim)).filter(_._2.nonEmpty)

      val logLevel: Level = env.get("LOG_LEVEL").map(Level.valueOf).getOrElse(default.logLevel)
      val host: String = env.getOrElse("SERVER_HOST", default.host)
      val port: Int = env.get("SERVER_PORT").map(_.toInt).getOrElse(default.port)
      val script: String = env.get("SCRIPT").map(_.replaceAll("\\r?\\n", "\n")).getOrElse(
        throw new IllegalArgumentException("Environment variable SCRIPT must not be empty!")
      )

      Options(
        logLevel = logLevel,
        host = host,
        port = port,
        script = script
      )
    }
  }

  private val blocker: Resource[Task, Blocker] = Blocker[Task]

  def runScript(script: String): Task[(ExitCode, Seq[String])] =
    for {
      result <- blocker.use { blocker =>
        val prox: ProxFS2[Task] = ProxFS2[Task](blocker)
        import prox._
        implicit val runner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner()
        val proc = Process("bash", List("-c", script))
        proc.toVector(fs2.text.utf8Decode).run()
      }
    } yield
      (result.exitCode, result.output)

  class Server(options: Options) {
    lazy val run: Task[Nothing] = Task.deferAction { scheduler =>
      BlazeServerBuilder[Task](scheduler)
        .bindHttp(options.port, options.host)
        .withHttpApp(service.orNotFound)
        .resource
        .use(_ => Task.never)
    }

    lazy val service: HttpRoutes[Task] = AutoSlash(HttpRoutes.of[Task] {
      case GET -> Root / "health" =>
        Ok()

      case GET -> Root / "metrics" =>
        for {
          (exitCode, output) <- runScript(options.script)
          outputString = output.mkString("\n")
          response <-
            if (exitCode == ExitCode.Success)
              Ok(outputString)
            else
              InternalServerError(outputString)
        } yield
          response
    })
  }

}
