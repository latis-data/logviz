package latis.logviz

import cats.effect.IO
import cats.syntax.all.*
import java.time.LocalDateTime
import org.http4s.EventStream // Stream[IO, ServerSentEvent]
import org.http4s.HttpRoutes
import org.http4s.ServerSentEvent
import org.http4s.StaticFile
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import org.http4s.MediaType

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
object LogvizRoutes extends Http4sDsl[IO] {
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
      val start: LocalDateTime = LocalDateTime.now().minusHours(24)
      val end: LocalDateTime = LocalDateTime.now()

      // for now, always doing events.json, splunk is optional
      val jsonEvents = JSONEventSource().getEvents(start, end)
      val splunkEvents = SplunkEventSource().getEvents(start, end)

      val sse: EventStream[IO] = (jsonEvents ++ splunkEvents).map(eventToServerSent)

      Ok(sse).map(_.putHeaders(`Content-Type`(MediaType.`text/event-stream`)))
  }
}

def eventToServerSent(event: Event): ServerSentEvent = {
  event match {
    case Event.Start(time) =>
      val jsonString: String = s"""{"eventType":"Start","time":"$time"}"""
      ServerSentEvent(Some(jsonString), Some("Start"))
    case Event.Request(id, time, request) => 
      val req: String = request.replace("\"", """\"""") // escaping quotes in the request string
      val jsonString: String = s"""{"eventType":"Request","id":"$id","time":"$time","request":"$req"}"""
      ServerSentEvent(Some(jsonString), Some("Request"))
    case Event.Response(id, time, status) =>
      val jsonString: String = s"""{"eventType":"Response","id":"$id","time":"$time","status":"$status"}"""
      ServerSentEvent(Some(jsonString), Some("Response"))
    case Event.Success(id, time, duration) =>
      val jsonString: String = s"""{"eventType":"Success","id":"$id","time":"$time","duration":"$duration"}"""
      ServerSentEvent(Some(jsonString), Some("Success"))
    case Event.Failure(id, time, msg) =>
      val jsonString: String = s"""{"eventType":"Failure","id":"$id","time":"$time","msg":"$msg"}"""
      ServerSentEvent(Some(jsonString), Some("Failure"))
  }
}