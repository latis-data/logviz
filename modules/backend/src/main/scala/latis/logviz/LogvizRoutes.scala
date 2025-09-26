package latis.logviz

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import java.time.LocalDateTime
import org.http4s.EventStream
import org.http4s.HttpRoutes
import org.http4s.ServerSentEvent
import org.http4s.StaticFile
import org.http4s.dsl.Http4sDsl

import latis.logviz.model.Event

/** 
 * Defines Routes
 * 
 * Following routes are created:
 * - GET / (index.html)
 * - GET /main.js
 * - GET /events.json 
 * - GET /events
 * 
 * Get each file from the resources folder, else return notFound
*/
class LogvizRoutes(eventsource: EventSource) extends Http4sDsl[IO] {
  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root =>
      StaticFile.fromResource("index.html", req.some).getOrElseF(NotFound())

    case req @ GET -> Root / "main.js" =>
      StaticFile.fromResource("main.js", req.some).getOrElseF(NotFound())

    case req @ GET -> Root / "events.json" =>
      StaticFile.fromResource("events.json", req.some).getOrElseF(NotFound())

    case req @ GET -> Root / "styles.css" =>
      StaticFile.fromResource("styles.css", req.some).getOrElseF(NotFound())

    case req @ GET -> Root / "events" =>
      // hard coding times for now
      // val start: LocalDateTime = LocalDateTime.now().minusHours(24)
      val start: LocalDateTime = LocalDateTime.now().toLocalDate().atStartOfDay()
      val end: LocalDateTime = LocalDateTime.now()
      // println(end)

      val events = eventsource.getEvents(start, end)
      val sse: EventStream[IO] = events.map(eventToServerSent)

      Ok(sse)
  }

  private def eventToServerSent(event: Event): ServerSentEvent = {
    val json = event.asJson
    ServerSentEvent(json.noSpaces.some, json.asObject.flatMap(_("eventType").map(_.noSpaces)))
  }
}