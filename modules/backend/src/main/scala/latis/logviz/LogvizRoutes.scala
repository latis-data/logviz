package latis.logviz

import java.time.LocalDateTime
import java.time.ZoneOffset

import scala.util.Try
import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import org.http4s.EventStream
import org.http4s.HttpRoutes
import org.http4s.ServerSentEvent
import org.http4s.StaticFile
import org.http4s.dsl.Http4sDsl
import org.http4s.ParseFailure
import org.http4s.QueryParamDecoder

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

    // event with query params
    case req @ GET -> Root / "events" :? StartDateQueryParamMatcher(startTime) +& EndDateQueryParamMatcher(endTime) =>

      startTime.fold(
        _     => BadRequest("invalid start date"),
        start => {
          endTime.fold(
            _   => BadRequest("invalid end date"),
            end => {
              val events = eventsource.getEvents(start, end)
              val sse: EventStream[IO] = events.map(eventToServerSent)
              Ok(sse)
            }
          )
        }
      )
    
    //unused right now
    //default (past 24 hours)
    case req @ GET -> Root / "events" =>
      // hard coding times for now
      val end: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
      val start: LocalDateTime = end.minusHours(24)
      val events = eventsource.getEvents(start, end)
      val sse: EventStream[IO] = events.map(eventToServerSent)

      Ok(sse)
  }

  private def eventToServerSent(event: Event): ServerSentEvent = {
    val json = event.asJson
    ServerSentEvent(json.noSpaces.some, json.asObject.flatMap(_("eventType").map(_.noSpaces)))
  }

  implicit val dateQueryParamDecoder: QueryParamDecoder[LocalDateTime] =
    //https://www.youtube.com/watch?v=v_gv6LsWdT0 36:20 Rock the JVM
    QueryParamDecoder[String].emap { dateString => 
      Try(LocalDateTime.parse(dateString))
        .toEither
        .leftMap { e =>
          ParseFailure(e.getMessage, e.getMessage)
        }
    }

  object StartDateQueryParamMatcher extends ValidatingQueryParamDecoderMatcher[LocalDateTime]("startTime")
  object EndDateQueryParamMatcher extends ValidatingQueryParamDecoderMatcher[LocalDateTime]("endTime")
  
}