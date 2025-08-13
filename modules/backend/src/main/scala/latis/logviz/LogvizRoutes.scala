package latis.logviz

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import fs2.Stream
import fs2.data.json.ast
import fs2.data.json.circe.*
import fs2.data.text.utf8.byteStreamCharLike
import fs2.io.readClassLoaderResource
import io.circe.Json
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
      val configSplunkClient: Boolean = sys.env.getOrElse("CONFIGURE_SPLUNK_CLIENT", "false").toBoolean

      if configSplunkClient then
        // optionally, get events from splunkclient
        val runSC: Resource[IO, SplunkClient] = for {
          splunkConf <- Resource.eval(ConfigSource.default.at("logviz.splunk").loadF[IO, SplunkConfig]()) // Resource[IO, SplunkConfig]
          client     <- SplunkClient.make(splunkConf.uri, splunkConf.username, splunkConf.password) // Resource[IO, SplunkClient]
        } yield client

        runSC.use { sclient =>
          val eventStream: Stream[IO, Event] = sclient.query()

          // checking how much is coming through the stream

          //
          Ok(
            "Testing..."
          )

          //Ok(eventStream)
        }
      else 
        // getting events from events.json
        val rawtext: Stream[IO, Byte] = readClassLoaderResource[IO]("events.json")

        val rawjson: Stream[IO, Json] = rawtext.through(ast.parse)

        val parsedJson: Stream[IO, Event] = rawjson.flatMap { fulljson =>
          val decodedResult = fulljson.hcursor.as[List[Json]]
        
          decodedResult match {
            case Right(jsonList) => 
              val events: List[Event] = jsonList.map(_.as[Event].toOption.get) // mapping to Event type
              Stream.emits(events).covary[IO]
            case Left(error) =>
              Stream.raiseError[IO](new Exception(s"Error parsing events.json into event stream with error: $error"))
          }
        }

        Ok(parsedJson)
  }
}