package latis.logviz.model

import cats.syntax.all.*
import io.circe.Decoder
import io.circe.DecodingFailure

/**
 * Data Model for Log Events
 * 
 * Representation of different types of log events that we may see 
 * and the information that we want to represent on logviz
 */
enum Event{

  /** The time of server starting or restarting */
  case Start(time: Long) 

  /** The time of a request sent as well as the contents of the request such as the dataset, its format, queries and operations */
  case Request(time: Long, request: String)

  /** The time of the response to the request, with a status code(ex: 202, 404, 503) */
  case Response(time: Long, status: Int)

  /** Successful outcome for retrieving the requested data. Time of success given as well as duration which is the time of the request to getting requested data */
  case Success(time: Long, duration: Long) 
  
  /** Failed to retrieve requested data. Message included about error */
  case Failure(time: Long, msg: String)
}

/**
 * Decoding instructions for parsing different types of events
 * 
 * For each event object in JSON file
 *  - Find "eventType" attribute and match each log event. If match, then grab required arguments
 *  - For each log event type, get its required arguments and return either a type of event or a failure
 *  - For multiple arguments, if any of the arguments come up as failure, then the whole yield is also a decoding failure
 * 
 * @return decoder either returns a failure(left) or an Event(right)
 */
object Event {
  given Decoder[Event] = Decoder.instance { cursor =>
    val ev = cursor.downField("eventType")
    ev.as[String].flatMap {
      case "Start"    => 
        cursor.downField("time").as[Long].map(t => Start(t))
      
      case "Request"  => 
        for {
          t <- cursor.downField("time").as[Long]
          r <- cursor.downField("request").as[String]
        } yield Request(t,r)
      
      case "Response" => 
        for {
          t <- cursor.downField("time").as[Long]
          s <- cursor.downField("status").as[Int]
        } yield Response(t, s)
      
      case "Success"  => 
        for {
          t <- cursor.downField("time").as[Long]
          d <- cursor.downField("duration").as[Long]
        } yield Success(t, d)

      case "Failure"  => 
        for {
          t <- cursor.downField("time").as[Long]
          m <- cursor.downField("msg").as[String]
        } yield Failure(t, m)
        
      case typ => DecodingFailure(s"Unknown event type: $typ", ev.history).asLeft
    }
  }
}
