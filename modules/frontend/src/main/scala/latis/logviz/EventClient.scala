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
              .through(ServerSentEvent.decoder[IO]) // Pipe[IO, Byte, SSE]
              .map{
                case ServerSentEvent(None, None, None, None, None) => None
                case sse => 
                  sse.data match {
                  case Some(event) => 
                    parse(event) match {
                    case Right(json) => json.as[Event] match {
                      case Right(event) => Some(event)
                      case Left(error) => throw new Exception(s"Error parsing json to event with error $error")
                    }
                    case Left(error) => throw new Exception(s"Error parsing server sent event to json with error $error")
                  }
                  case None => throw new Exception(s"Server sent event missing data field")
                }
              }
              .collect{case Some(event) => event} // getting rid of the None SSE
          }
      }
    }
}

def serverSentToEvent(event: ServerSentEvent): Event = {
  val line: Array[String] = event.data match {
    case Some(data) => 
      data.split("time: ")
    case None => 
      throw new Exception("Server sent event missing data field")
  }

  event.eventType match {
    case Some("Start") =>
      val time: String = line(1)
      Event.Start(time)
    case Some("Request") => 
      val id: String = line(0).split("id: ")(1).dropRight(1)
      val time: String = line(1).split("request: ")(0).dropRight(1)
      val request: String = line(1).split("request: ")(1)
      Event.Request(id, time, request)
    case Some("Response") =>
      val id: String = line(0).split("id: ")(1).dropRight(1)
      val time: String = line(1).split("status: ")(0).dropRight(1)
      val status: Int = line(1).split("status: ")(1).toInt
      Event.Response(id, time, status)
    case Some("Success") =>
      val id: String = line(0).split("id: ")(1).dropRight(1)
      val time: String = line(1).split("duration: ")(0).dropRight(1)
      val duration: Long = line(1).split("duration: ")(1).toLong
      Event.Success(id, time, duration)
    case Some("Failure") =>
      val id: String = line(0).split("id: ")(1).dropRight(1)
      val time: String = line(1).split("msg: ")(0).dropRight(1)
      val msg: String = line(1).split("msg: ")(1)
      Event.Failure(id, time, msg)
    case Some(_) => throw new Exception("Error parsing server sent event to event")
    case None => throw new Exception("Error parsing server sent event to event")
  }
}