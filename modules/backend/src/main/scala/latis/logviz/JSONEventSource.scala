package latis.logviz

import java.time.LocalDateTime
import java.time.ZoneOffset

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.std.Supervisor
import cats.syntax.all.*
import cats.effect.std.Queue
import cats.effect.std.CountDownLatch
import fs2.data.json.ast
import fs2.data.json.circe.*
import fs2.data.text.utf8.byteStreamCharLike
import fs2.io.readClassLoaderResource
import fs2.Stream

import latis.logviz.model.Event

val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

/**
  * Test event source. Pulling dummy data from a test JSON file
  */
class JSONEventSource extends EventSource {
  override def getEvents(start: LocalDateTime, end: LocalDateTime): Stream[IO, Event] =

    val byteStream: Stream[IO, Byte] = readClassLoaderResource[IO]("events.json")
    
    val decodedJson: Stream[IO, List[Event]] = byteStream
      .through(ast.parse)
      .flatMap{ j => 
        j.as[List[Event]] match {
          case Right(events) => Stream.emit(events)
          case Left(error) => 
            Stream.raiseError[IO](new Exception(s"Decoding events.json failed. error: $error"))
            
        }
       }

      decodedJson.map{ events => 
        //list of events separated into past and future events based on the offset
        //positive offsets indicate future events
        val (past, future) = events.partition {
            case Event.Start(time) => time.toLong <= 0
            case Event.Request(id, time, request) => time.toLong <= 0
            case Event.Response(id, time, status) => time.toLong <= 0
            case Event.Success(id, time, duration) => time.toLong <= 0
            case Event.Failure(id, time, msg) => time.toLong <= 0
          }
        val count = future.length
        (past, future, count)
      }.flatMap{ (past, future, count) =>
          //calculating time for event based on current time
          //makes it easier for distinguishing future and past events
          //if offset was off start time, then negative offset events would never get drawn 
          //and harder to partition positive offset events into past and future
          val curr = LocalDateTime.now(ZoneOffset.UTC)
          Stream.eval(Queue.unbounded[IO, Option[Event]]).flatMap{ queue => 
              Stream.eval(past.traverse{e => 
                  val event = eventOffsetToTime(e, curr)
                  val time = getEventTime(event)
                  //no need to have events outside of start time as part of the stream
                  if (time.isBefore(start)) {
                    IO.unit
                  } else {
                    queue.offer(Some(event))
                  }
                }) >>
              //stream that'll run in the background
              //termination of this "in" stream will terminate the out stream
              Stream.fromQueueNoneTerminated(queue).concurrently{
                (
                  Stream.resource(Supervisor[IO](await = true)), 
                  Stream.eval(CountDownLatch[IO](count))
                ).flatMapN { (sup, c) => 
                  Stream.evalSeq(
                    future.traverse{ e => 
                      val event = eventOffsetToTime(e, curr)
                      val time = getEventTime(event)
                      val timer = java.time.Duration.between(LocalDateTime.now(ZoneOffset.UTC), time).toMillis()
                      sup.supervise(IO.sleep(timer.milliseconds) >> queue.offer(Some(event)) >> c.release).void
                    }) ++ Stream.eval(c.await) ++ Stream.eval(queue.offer(None)) 
                }
              }
            }
      }
}

private def getEventTime(event: Event): LocalDateTime = 
  event match {
    case Event.Start(time) => LocalDateTime.parse(time, formatter)
    case Event.Request(id, time, request) => LocalDateTime.parse(time, formatter)
    case Event.Response(id, time, status) => LocalDateTime.parse(time, formatter)
    case Event.Success(id, time, duration) => LocalDateTime.parse(time, formatter)
    case Event.Failure(id, time, msg) => LocalDateTime.parse(time, formatter)
  }

private def eventOffsetToTime(event: Event, requestTime: LocalDateTime): Event =
  event match {
    case Event.Start(time) => 
      val seconds = time.toLong / 1000
      val dateTime = requestTime.plusSeconds(seconds).format(formatter)
      Event.Start(dateTime)
    case Event.Request(id, time, request) =>
      val seconds = time.toLong / 1000
      val dateTime = requestTime.plusSeconds(seconds).format(formatter)
      Event.Request(id, dateTime, request)
    case Event.Response(id, time, status) =>
      val seconds = time.toLong / 1000
      val dateTime = requestTime.plusSeconds(seconds).format(formatter)
      Event.Response(id, dateTime, status)
    case Event.Success(id, time, duration) =>
      val seconds = time.toLong / 1000
      val dateTime = requestTime.plusSeconds(seconds).format(formatter)
      Event.Success(id, dateTime, duration)
    case Event.Failure(id, time, msg) =>
      val seconds = time.toLong / 1000
      val dateTime = requestTime.plusSeconds(seconds).format(formatter)
      Event.Failure(id, dateTime, msg)
  }
  
