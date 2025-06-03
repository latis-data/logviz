package latis.logviz

import cats.effect.IO
import cats.syntax.all.*
import org.http4s.HttpRoutes
import org.http4s.StaticFile
import org.http4s.dsl.Http4sDsl

object LogvizRoutes extends Http4sDsl[IO] {
  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root =>
      StaticFile.fromResource("index.html", req.some).getOrElseF(NotFound())

    case req @ GET -> Root / "main.js" =>
      StaticFile.fromResource("main.js", req.some).getOrElseF(NotFound())

    case req @ GET -> Root / "events.json" =>
      StaticFile.fromResource("events.json", req.some).getOrElseF(NotFound())
  }
}
