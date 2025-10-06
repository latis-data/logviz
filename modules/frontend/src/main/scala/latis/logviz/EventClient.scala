package latis.logviz

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import fs2.dom.Window
import io.circe.parser.*
import org.http4s.Method
import org.http4s.Request
import org.http4s.ServerSentEvent
import org.http4s.Uri
import org.http4s.client.Client

import latis.logviz.model.Event

/** EventClient used to return stream of events */
trait EventClient {
  def getEvents: Stream[IO, Event]
}

object EventClient {
  
  /** 
    * Creates EventClient
    * 
    * Creates baseURI @example localhost:8080 
    * 
    * EntityDecoder used to decode JSON which is defined in Event
    * 
    * Create an EventClient instance to expect a list of events after decoding event.json
    * 
    * @param http Client from http4s [[https://http4s.github.io/http4s-dom/fetch.html]]
    */
  def fromHttpClient(http: Client[IO]): IO[EventClient] =
    (
      Window[IO].location.protocol.get,
      Window[IO].location.host.get
    ).mapN { (protocol, host) =>
      val baseUri = Uri.unsafeFromString(s"$protocol//$host")

      new EventClient {
        override def getEvents: Stream[IO, Event] =
          val request = Request[IO](method = Method.GET, uri = baseUri / "events")
          
          http.stream(request).flatMap{ res =>
            res.body
              .through(ServerSentEvent.decoder[IO])
              .map{
                case ServerSentEvent(Some(data), _, _, _, _) =>
                  parse(data) match {
                    case Right(json) => json.as[Event] match {
                      case Right(event) => Some(event)
                      case Left(error) => throw new Exception(s"Error parsing json to event with error $error")
                    }
                    case Left(error) => throw new Exception(s"Error parsing server sent event to json with error $error")
                  }
                case _ => None
              }
              .unNone
          }
      }
    }
}