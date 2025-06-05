package latis.logviz.model

import cats.syntax.all.*
import io.circe.Decoder
import io.circe.DecodingFailure

enum Event {
  case Start(time: Long) 
  case Request(time: Long, request: String)
  case Response(time: Long, status: Int)
  case Success(time: Long, duration: Long) 
  case Failure(time: Long, msg: String)
}

object Event {
  given Decoder[Event] = Decoder.instance { cursor =>
    val ev = cursor.downField("eventType")
    ev.as[String].flatMap {
      case "Start" => 
        cursor.downField("time").as[Long].map(t => Start(t))
      
      case "Request" => 
        for {
          t <- cursor.downField("time").as[Long]
          r <- cursor.downField("request").as[String]
        } yield Request(t,r)
      
      case "Response" => 
        for {
          t <- cursor.downField("time").as[Long]
          s <- cursor.downField("status").as[Int]
        } yield Response(t, s)
      
      case "Success" => 
        for {
          t <- cursor.downField("time").as[Long]
          d <- cursor.downField("duration").as[Long]
        } yield Success(t, d)

      case "Failure" => 
        for {
          t <- cursor.downField("time").as[Long]
          m <- cursor.downField("msg").as[String]
        } yield Failure(t, m)
        
      case typ => DecodingFailure(s"Unknown event type: $typ", ev.history).asLeft
    }
  }
}
