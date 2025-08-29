package latis.logviz

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import fs2.Stream
import fs2.data.json.ast
import fs2.data.json.circe.*
import fs2.data.text.utf8.byteStreamCharLike
import fs2.io.readClassLoaderResource
import org.http4s.EventStream // Stream[IO, ServerSentEvent]
import org.http4s.HttpRoutes
import org.http4s.ServerSentEvent
import org.http4s.StaticFile
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import org.http4s.MediaType
import pureconfig.* 
import pureconfig.module.catseffect.syntax.*

import latis.logviz.model.Event
import latis.logviz.splunk.*

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
      val eventClient: Resource[IO, Option[SplunkClient]] = for {
        splunkConf <- Resource.eval(ConfigSource.default.at("logviz.splunk").loadF[IO, SplunkConfig]())
        client     <- splunkConf match {
          case SplunkConfig.Disabled => Resource.pure[IO, Option[SplunkClient]](None)
          case SplunkConfig.Enabled(uri, username, password) => SplunkClient.make(uri, username, password).map(Some(_))
        }
      } yield client

      eventClient.use { 
        case Some(sclient: SplunkClient) => 
          // optionally, get events from splunkclient
          val eventStream: Stream[IO, Event] = sclient.query()

          // turning into sse
          val sse: EventStream[IO] = eventStream.map(eventToServerSent)

          Ok(sse).map(_.putHeaders(`Content-Type`(MediaType.`text/event-stream`)))

        case None =>
          // getting events from events.json
          val byteStream: Stream[IO, Byte] = readClassLoaderResource[IO]("events.json")

          val decodedJson: Stream[IO, Event] = byteStream
            .through(ast.parse)
            .flatMap{j => 
              j.as[List[Event]] match {
                case Right(events) => Stream.emits(events)
                case Left(error) => 
                  Stream.raiseError[IO](new Exception(s"Decoding events.json failed with error: $error"))
              }
            }

          // turning into sse
          val sse: EventStream[IO] = decodedJson.map(eventToServerSent)

          Ok(sse).map(_.putHeaders(`Content-Type`(MediaType.`text/event-stream`)))
      }
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