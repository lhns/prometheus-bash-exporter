package de.lolhens.prometheus.bashexporter

import cats.effect._
import cats.effect.kernel.Deferred
import io.github.vigoo.prox.ProxFS2

import scala.concurrent.duration.{Duration, FiniteDuration}

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
        atomicCache.modify { cache =>
          cache.get(args) match {
            case Some(io) =>
              (cache, io)

            case None =>
              val deferred = Deferred.unsafe[IO, (ExitCode, String)]
              val io =
                atomicCache.update(_ - args).delayBy(ttl).start >>
                  runWithoutCache(args).flatTap(deferred.complete)

              (cache + (args -> deferred.get), io)
          }
        }.flatten
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
