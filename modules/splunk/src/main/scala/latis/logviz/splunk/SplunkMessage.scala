package latis.logviz.splunk

import cats.syntax.all.*
import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Json
import io.circe.parser

opaque type SplunkMessage = Json

object SplunkMessage:
    //def apply(s: List[Json]): List[SplunkMessage] = s
    def apply(s: Json): SplunkMessage = s
end SplunkMessage 

given Decoder[SplunkMessage] = Decoder.instance { cursor => // defining how to decode a JSON value into a list of SplunkMessage
  // Pull out the _raw bit, find the message in there
  for {
    raw <- cursor.downField("_raw").as[String]
    parsed <- parser.parse(raw).left.map(err =>
      DecodingFailure(s"Failed to parse _raw Json: $err}", cursor.history)
    )
  } yield SplunkMessage(parsed)
}