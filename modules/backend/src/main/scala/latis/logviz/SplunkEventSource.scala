package latis.logviz

import java.time.format.DateTimeParseException
import java.time.LocalDateTime

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream

import latis.logviz.model.Event
import latis.logviz.splunk.*

class SplunkEventSource(sclient: SplunkClient) extends EventSource {
  override def getEvents(start: LocalDateTime, end: LocalDateTime): Stream[IO, Event] =
    val eventStream: Stream[IO, Event] = sclient.query()

    eventStream.filter{ event =>
      event match {
        case Event.Start(time) =>
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
    }
}