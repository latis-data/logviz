package latis.logviz

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import fs2.Stream
import fs2.data.json.ast
import fs2.data.json.circe.*
import fs2.data.text.utf8.byteStreamCharLike
import fs2.io.readClassLoaderResource
import org.http4s.HttpRoutes
import org.http4s.StaticFile
import org.http4s.dsl.Http4sDsl
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

          // counting the amount of events that were matched -- this will print parsing message twice
          val count: IO[Unit] = eventStream.compile.count.flatMap { count =>
            IO.println(s"Processed $count events...")
          }

          count *> Ok(eventStream)
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

          Ok(decodedJson)
      }
  }
}