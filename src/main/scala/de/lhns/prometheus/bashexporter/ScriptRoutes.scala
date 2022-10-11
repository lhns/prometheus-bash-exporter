package de.lhns.prometheus.bashexporter

import cats.data.OptionT
import cats.effect._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.server.middleware.AutoSlash

import scala.util.chaining._

class ScriptRoutes(scripts: Seq[Script]) {
  val toRoutes: HttpRoutes[IO] =
    HttpRoutes[IO] {
      case GET -> Root / "health" =>
        OptionT.liftF(Ok())

      case GET -> path =>
        val pathList = path.segments.map(_.encoded.toLowerCase)
        for {
          (script, subPath) <- OptionT.fromOption[IO] {
            scripts.collectFirst {
              case script if pathList.startsWith(script.route) =>
                (script, pathList.drop(script.route.length))
            }
          }
          (exitCode, output) <- OptionT.liftF(script.run(subPath))
          response <- OptionT.liftF {
            exitCode match {
              case ExitCode.Success => Ok(output)
              case _ => InternalServerError(output)
            }
          }
        } yield
          response
    }.pipe(AutoSlash(_))
}
