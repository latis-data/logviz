package latis.logviz.model

import cats.syntax.all.*
import io.circe.Decoder
import io.circe.DecodingFailure

enum Event {
  case Hello
  case World
}

object Event {
  given Decoder[Event] = Decoder.instance { cursor =>
    val ev = cursor.downField("event")
    ev.as[String].flatMap {
      case "hello" => Event.Hello.asRight
      case "world" => Event.World.asRight
      case typ => DecodingFailure(s"Unknown event type: $typ", ev.history).asLeft
    }
  }
}
