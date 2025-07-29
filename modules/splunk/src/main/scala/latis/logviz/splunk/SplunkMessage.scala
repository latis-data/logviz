package latis.logviz.splunk

import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Json
import io.circe.parser

opaque type SplunkMessage = Json

object SplunkMessage:
    def apply(s: Json): SplunkMessage = s
    
    extension (m: SplunkMessage)
      def hcursor = m.hcursor
      
end SplunkMessage 

given Decoder[SplunkMessage] = Decoder.instance { cursor =>
  for {
    raw <- cursor.downField("_raw").as[String]
    parsed <- parser.parse(raw).left.map(err =>
      DecodingFailure(s"Failed to parse _raw Json: $err}", cursor.history)
    )
  } yield SplunkMessage(parsed)
}