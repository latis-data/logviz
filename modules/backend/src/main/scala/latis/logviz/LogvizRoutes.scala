package latis.logviz

import cats.effect.IO
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
      // getting the contents of the file
      val rawtext: Stream[IO, Byte] = readClassLoaderResource[IO]("events.json")

      // transforming to a stream of json
      val rawjson: Stream[IO, Json] = rawtext.through(ast.parse)

      val parsedJson: Stream[IO, Event] = rawjson.flatMap { fulljson =>
        // separating the full json into json objects for each event
        val decodedResult = fulljson.hcursor.as[List[Json]]
      
        // parse into Events
        decodedResult match {
          case Right(jsonList) => 
            val events: List[Event] = jsonList.map(_.as[Event].toOption.get) // mapping to Event type
            println(events)
            Stream.emits(events).covary[IO]
          case Left(error) =>
            Stream.raiseError[IO](new Exception(s"Error parsing events.json into event stream with error: $error"))
        }
      }

      Ok(parsedJson)
  }
}