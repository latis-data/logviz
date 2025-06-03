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

trait EventClient {
  def getEvents: Stream[IO, Event]
}

object EventClient {
  def fromHttpClient(http: Client[IO]): IO[EventClient] =
    (
      Window[IO].location.protocol.get,
      Window[IO].location.host.get
    ).mapN { (protocol, host) =>
      val baseUri = Uri.unsafeFromString(s"$protocol//$host")

      given eventDecoder: EntityDecoder[IO, List[Event]] = jsonOf

      new EventClient {
        override def getEvents: Stream[IO, Event] =
          Stream.evals(http.expect[List[Event]](baseUri / "events.json"))
      }
    }
}
