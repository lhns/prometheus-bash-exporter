package de.lolhens.prometheus.bashexporter

import cats.data.OptionT
import cats.effect.{Blocker, ExitCode, Resource}
import cats.syntax.semigroupk._
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
                     scripts: Seq[(Seq[String], Seq[String])],
                    ) {
    def debug: String = {
      s"""LOG_LEVEL: $logLevel
         |SERVER_HOST: $host
         |SERVER_PORT: $port
         |${
        scripts.map {
          case (route, script) =>
            (s"SCRIPT${route.map("_" + _.toUpperCase).mkString}", script.map("\n  " + _).mkString)
        }.sortBy(_._1).map(e => s"${e._1}:${e._2}").mkString("\n")
      }""".stripMargin
    }
  }

  object Options {
    val default: Options = Options(
      logLevel = Level.INFO,
      host = "0.0.0.0",
      port = 8080,
      scripts = Seq.empty
    )

    private val ScriptRoutePattern = "^(?i)SCRIPT(?-i)(?:_([^_]+(?:_[^_]+)*))?$".r

    def fromEnv: Options = {
      val env: Map[String, String] = System.getenv().asScala.toMap.map(e => (e._1, e._2.trim)).filter(_._2.nonEmpty)

      val logLevel: Level = env.get("LOG_LEVEL").map(Level.valueOf).getOrElse(default.logLevel)
      val host: String = env.getOrElse("SERVER_HOST", default.host)
      val port: Int = env.get("SERVER_PORT").map(_.toInt).getOrElse(default.port)
      val scripts: Seq[(Seq[String], Seq[String])] = env.iterator.collect {
        case (ScriptRoutePattern(routeString), scriptString) if !scriptString.isBlank =>
          val route = Option(routeString).map(_.split("_").iterator.map(_.toLowerCase).toSeq).getOrElse(Seq.empty)
          val script = scriptString.split("\\r?\\n").toSeq
          (route, script)
      }.toSeq.sortBy(_._1.size).reverse
      require(scripts.nonEmpty, "Environment variable SCRIPT must not be empty!")

      Options(
        logLevel = logLevel,
        host = host,
        port = port,
        scripts = scripts
      )
    }
  }

  private val blocker: Resource[Task, Blocker] = Blocker[Task]

  def runScript(script: String, args: Seq[String]): Task[(ExitCode, Seq[String])] =
    for {
      result <- blocker.use { blocker =>
        val prox: ProxFS2[Task] = ProxFS2[Task](blocker)
        import prox._
        implicit val runner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner()
        val proc = Process("bash", List("-c", script, "bash") ++ args)
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


    lazy val service: HttpRoutes[Task] = AutoSlash {
      HttpRoutes.of[Task] {
        case GET -> Root / "health" =>
          Ok()
      } <+> HttpRoutes {
        case GET -> path =>
          val pathList = path.toList.map(_.toLowerCase)
          for {
            (script, subPath) <- OptionT.fromOption[Task] {
              options.scripts.collectFirst {
                case (route, script) if pathList.startsWith(route) =>
                  (script.mkString("\n"), pathList.drop(route.length))
              }
            }
            (exitCode, output) <- OptionT.liftF(runScript(script, subPath))
            outputString = output.mkString("\n")
            response <- OptionT.liftF {
              if (exitCode == ExitCode.Success)
                Ok(outputString)
              else
                InternalServerError(outputString)
            }
          } yield
            response
      }
    }
  }

}
