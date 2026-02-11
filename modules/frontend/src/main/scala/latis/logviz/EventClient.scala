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
import java.time.LocalDateTime

/** EventClient used to return stream of events */
trait EventClient {
  def getEvents(startTime: String, endTime: Option[String]): Stream[IO, Event]

  //no given start and end time: past 24 hours + live
  def getEvents: Stream[IO, Event] =
    val curr = LocalDateTime.now(java.time.ZoneId.of("UTC"))
    val start = curr.toLocalDate().atStartOfDay().toString()
    getEvents(start, None)

  //no end time (live)
  def getEvents(start: String): Stream[IO, Event] = 
    getEvents(start, None)

  def getEvents(start: String, end: String): Stream[IO, Event] = 
    getEvents(start, Some(end))
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
        override def getEvents(startTime: String, endTime: Option[String]): Stream[IO, Event] =         
          
          val uri = endTime match {
            case None => 
              //should there be a live query parameter?
              // println("no end time. LIVE!!")
              val endTime = LocalDateTime.now(java.time.ZoneId.of("UTC"))
              val end = endTime.toString()
              Uri.unsafeFromString(s"$baseUri/events?startTime=$startTime&endTime=$end")
            
            case Some(value) => 
              // println(s"end time given! $value")
              //for some reason it seems like we're not making a new http request when I change the end date? 
              //changing from live to not live also doesnt seem to make a new http request, or at 
              Uri.unsafeFromString(s"$baseUri/events?startTime=$startTime&endTime=$value")
          }

          val request = Request[IO](method = Method.GET, uri = uri)
          // println(uri)
          // val request = Request[IO](method = Method.GET, uri = baseUri / "events")
          
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