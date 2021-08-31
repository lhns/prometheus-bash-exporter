package de.lolhens.prometheus.bashexporter

import cats.data.OptionT
import cats.effect._
import cats.effect.kernel.Deferred
import cats.syntax.either._
import cats.syntax.semigroupk._
import ch.qos.logback.classic.{Level, Logger}
import io.github.vigoo.prox.ProxFS2
import org.http4s._
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.server.middleware.AutoSlash

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.jdk.CollectionConverters._

object Main extends IOApp {
  private def setLogLevel(level: Level): Unit = {
    val rootLogger = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger]
    rootLogger.setLevel(level)
  }

  override def run(args: List[String]): IO[ExitCode] = IO.defer {
    val options = Options.fromEnv
    println(options.debug + "\n")
    setLogLevel(options.logLevel)
    new Server(options).run.use(_ => IO.never)
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
      logLevel = Level.ERROR,
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

    def runWithoutCache(args: Seq[String]): IO[(ExitCode, String)] = {
      val prox: ProxFS2[IO] = ProxFS2[IO]
      import prox._
      implicit val runner: ProcessRunner[JVMProcessInfo] = new JVMProcessRunner()
      val proc = Process("bash", List("-c", script, "bash") ++ args)

      for {
        result <- proc.toVector(fs2.text.utf8.decode).run()
      } yield
        (result.exitCode, result.output.mkString)
    }

    private val atomicCache = Ref.unsafe[IO, Map[Seq[String], IO[(ExitCode, String)]]](Map.empty)

    def run(args: Seq[String]): IO[(ExitCode, String)] = {
      cacheTtl match {
        case None =>
          runWithoutCache(args)

        case Some(ttl) =>
          for {
            deferredOrIO <- atomicCache.modify { cache =>
              cache.get(args) match {
                case Some(io) =>
                  (cache, Either.right(io))

                case None =>
                  val deferred = Deferred.unsafe[IO, (ExitCode, String)]
                  (cache + (args -> deferred.get), Either.left(deferred))
              }
            }

            result <- deferredOrIO.fold(
              { deferred =>
                for {
                  result <- runWithoutCache(args).flatTap(deferred.complete)
                  _ <- (IO.sleep(ttl) >> atomicCache.update(_ - args)).start
                } yield
                  result
              },
              io => io
            )
          } yield
            result
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

  class Server(options: Options) {
    lazy val run: Resource[IO, Unit] =
      for {
        ec <- Resource.eval(IO.executionContext)
        _ <- BlazeServerBuilder[IO](ec)
          .bindHttp(options.port, options.host)
          .withHttpApp(service.orNotFound)
          .resource
      } yield ()

    lazy val service: HttpRoutes[IO] = AutoSlash {
      HttpRoutes.of[IO] {
        case GET -> Root / "health" =>
          Ok()
      } <+> HttpRoutes {
        case GET -> path =>
          val pathList = path.segments.map(_.encoded.toLowerCase)
          for {
            (script, subPath) <- OptionT.fromOption[IO] {
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
