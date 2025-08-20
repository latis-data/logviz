package latis.logviz

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import fs2.dom.Window
import org.http4s.EntityDecoder
import org.http4s.Uri
import org.http4s.circe.jsonOf
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

      given eventDecoder: EntityDecoder[IO, List[Event]] = jsonOf

      new EventClient {
        override def getEvents: Stream[IO, Event] =
          Stream.evals(http.expect[List[Event]](baseUri / "events"))
      }
    }
}
