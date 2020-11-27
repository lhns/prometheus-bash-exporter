package de.lolhens.prometheus.bashexporter

import cats.data.OptionT
import cats.effect.{Blocker, ExitCode, Resource}
import cats.syntax.either._
import cats.syntax.semigroupk._
import ch.qos.logback.classic.{Level, Logger}
import io.github.vigoo.prox.ProxFS2
import monix.eval.{Task, TaskApp}
import monix.execution.atomic.Atomic
import org.http4s._
import org.http4s.dsl.task._
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.AutoSlash

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Future, Promise}
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
                     scripts: Seq[Script],
                    ) {
    def debug: String = {
      s"""LOG_LEVEL: $logLevel
         |SERVER_HOST: $host
         |SERVER_PORT: $port
         |${
        scripts.map { script =>
          (script, script.scriptLines.map("\n  " + _).mkString)
        }.sortBy(_._1.variableName).map {
          case (script, scriptLines) =>
            s"${script.variableName}${script.cacheTtl.map(ttl => s" (cache for $ttl)").getOrElse("")}:${scriptLines}"
        }.mkString("\n")
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

    def fromEnv: Options = {
      val env: Map[String, String] = System.getenv().asScala.toMap.map(e => (e._1, e._2.trim)).filter(_._2.nonEmpty)

      val logLevel: Level = env.get("LOG_LEVEL").map(Level.valueOf).getOrElse(default.logLevel)
      val host: String = env.getOrElse("SERVER_HOST", default.host)
      val port: Int = env.get("SERVER_PORT").map(_.toInt).getOrElse(default.port)
      val cacheTtl: Map[Seq[String], FiniteDuration] = env.iterator.collect {
        case Script.RouteCache(route, ttl) => (route, ttl)
      }.toMap
      val scripts: Seq[Script] = env.iterator.collect {
        case Script.RouteScript(route, scriptLines) => Script(route, scriptLines, cacheTtl.get(route))
      }.toSeq.sortBy(_.route.size).reverse
      require(scripts.nonEmpty, "Environment variable SCRIPT must not be empty!")

      Options(
        logLevel = logLevel,
        host = host,
        port = port,
        scripts = scripts
      )
    }
  }

  case class Script(route: Seq[String],
                    scriptLines: Seq[String],
                    cacheTtl: Option[FiniteDuration]) {
    private lazy val script = scriptLines.mkString("\n")

    lazy val variableName: String = s"SCRIPT${route.map("_" + _.toUpperCase).mkString}"

    def runWithoutCache(args: Seq[String]): Task[(ExitCode, String)] =
      for {
        result <- blocker.use { blocker =>
          val prox: ProxFS2[Task] = ProxFS2[Task](blocker)
          import prox._
          implicit val runner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner()
          val proc = Process("C:\\Users\\U016595\\Downloads\\PortableGit-2.21.0-64-bit\\bin\\bash", List("-c", script, "bash") ++ args)
          proc.toVector(fs2.text.utf8Decode).run()
        }
      } yield
        (result.exitCode, result.output.mkString)

    private val atomicCache = Atomic(Map.empty[Seq[String], Future[(ExitCode, String)]])

    def run(args: Seq[String]): Task[(ExitCode, String)] = {
      cacheTtl match {
        case None =>
          runWithoutCache(args)

        case Some(ttl) =>
          Task.deferFutureAction { implicit scheduler =>
            val futureOrPromise: Either[Promise[(ExitCode, String)], Future[(ExitCode, String)]] =
              atomicCache.transformAndExtract { cache =>
                cache.get(args) match {
                  case Some(future) => (Either.right(future), cache)
                  case None =>
                    val promise = Promise[(ExitCode, String)]
                    (Either.left(promise), cache + (args -> promise.future))
                }
              }

            futureOrPromise.fold(
              { promise =>
                val resultFuture = runWithoutCache(args).runToFuture
                promise.completeWith(resultFuture)
                (Task.sleep(ttl) *> Task {
                  atomicCache.transform(_ - args)
                }).runAsyncAndForget
                resultFuture
              },
              future => future
            )
          }
      }
    }
  }

  object Script {

    object RouteScript {
      private val RouteScriptPattern = "^(?i)SCRIPT(?-i)(?:_([^_]+(?:_[^_]+)*))?$".r

      def unapply(entry: (String, String)): Option[(Seq[String], Seq[String])] = Some(entry).collect {
        case (RouteScriptPattern(routeString), scriptString) if !scriptString.isBlank =>
          val route = Option(routeString).map(_.split("_").iterator.map(_.toLowerCase).toSeq).getOrElse(Seq.empty)
          (route, scriptString.split("\\r?\\n").toSeq)
      }
    }

    object RouteCache {
      private val RouteCachePattern = "^(?i)CACHE(?-i)(?:_([^_]+(?:_[^_]+)*))?$".r

      private def requireFinite(duration: Duration, name: String): FiniteDuration = duration match {
        case finite: FiniteDuration => finite
        case _ => throw new IllegalArgumentException(s"$name must be finite!")
      }

      def unapply(entry: (String, String)): Option[(Seq[String], FiniteDuration)] = Some(entry).collect {
        case (key@RouteCachePattern(routeString), durationString) =>
          val route = Option(routeString).map(_.split("_").iterator.map(_.toLowerCase).toSeq).getOrElse(Seq.empty)
          (route, requireFinite(Duration(durationString), key))
      }
    }

  }

  private val blocker: Resource[Task, Blocker] = Blocker[Task]

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
                case script if pathList.startsWith(script.route) =>
                  (script, pathList.drop(script.route.length))
              }
            }
            (exitCode, output) <- OptionT.liftF(script.run(subPath))
            response <- OptionT.liftF {
              if (exitCode == ExitCode.Success)
                Ok(output)
              else
                InternalServerError(output)
            }
          } yield
            response
      }
    }
  }

}
