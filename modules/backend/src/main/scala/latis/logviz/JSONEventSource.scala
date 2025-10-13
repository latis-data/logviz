package latis.logviz

import java.time.format.DateTimeParseException
import java.time.LocalDateTime

import cats.effect.IO
import cats.syntax.all.*
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
    
    val decodedJson: Stream[IO, Event] = byteStream
      .through(ast.parse)
      .flatMap{ j => 
        j.as[List[Event]] match {
          case Right(events) => Stream.emits(events)
          case Left(error) => 
            Stream.raiseError[IO](new Exception(s"Decoding events.json failed. error: $error"))
        }
       }.map{
        //know for sure that this should be a parsable long since we already format it? or should I still catch exception?
        case Event.Start(time) => 
          Event.Start(offsetToLocalDateTime(time.toLong, start))
        case Event.Request(id, time, request) => 
          Event.Request(id, offsetToLocalDateTime(time.toLong, start), request)
        case Event.Response(id, time, status) => 
          Event.Response(id, offsetToLocalDateTime(time.toLong, start), status)
        case Event.Success(id, time, duration) =>
          Event.Success(id, offsetToLocalDateTime(time.toLong, start), duration)
        case Event.Failure(id, time, msg) =>
          Event.Failure(id, offsetToLocalDateTime(time.toLong, start), msg)
       }
      .filter{
        //even if I have negative offset values, they wouldnt be apart of the stream because they get filtered out if we're basing the offsets off the start time
        case Event.Start(time) =>
          //do I still need to catch datetimeparse exceptions if I know for sure this time string will be a valid localdatetime?
          Either.catchOnly[DateTimeParseException](LocalDateTime.parse(time, formatter)) match
            case Left(value) => throw new IllegalArgumentException(
              "Invalid time") 
            case Right(value) => value.isAfter(start) && value.isBefore(end)
        case Event.Request(id, time, request) =>
          Either.catchOnly[DateTimeParseException](LocalDateTime.parse(time, formatter)) match
            case Left(value) => throw new IllegalArgumentException(
              "Invalid time") 
            case Right(value) => value.isAfter(start) && value.isBefore(end)
        case Event.Response(id, time, status) =>
          Either.catchOnly[DateTimeParseException](LocalDateTime.parse(time, formatter)) match
            case Left(value) => throw new IllegalArgumentException(
              "Invalid time") 
            case Right(value) => value.isAfter(start) && value.isBefore(end)
        case Event.Success(id, time, duration) =>
          Either.catchOnly[DateTimeParseException](LocalDateTime.parse(time, formatter)) match
            case Left(value) => throw new IllegalArgumentException(
              "Invalid time") 
            case Right(value) => value.isAfter(start) && value.isBefore(end)
        case Event.Failure(id, time, msg) =>
          Either.catchOnly[DateTimeParseException](LocalDateTime.parse(time, formatter)) match
            case Left(value) => throw new IllegalArgumentException(
              "Invalid time") 
            case Right(value) => value.isAfter(start) && value.isBefore(end)
      }
    decodedJson
}

private def offsetToLocalDateTime(offset: Long, requestTime: LocalDateTime): String =
  val seconds = offset/1000
  requestTime.plusSeconds(seconds).format(formatter)
