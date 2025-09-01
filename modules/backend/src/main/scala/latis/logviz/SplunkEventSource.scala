package latis.logviz

import java.time.format.DateTimeParseException
import java.time.LocalDateTime

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import fs2.Stream
import pureconfig.* 
import pureconfig.module.catseffect.syntax.*

import latis.logviz.model.Event
import latis.logviz.splunk.*

class SplunkEventSource extends EventSource {
  override def getEvents(start: LocalDateTime, end: LocalDateTime): Stream[IO, Event] =

    val splunkClient: Resource[IO, Option[SplunkClient]] = for {
      splunkConf <- Resource.eval(ConfigSource.default.at("logviz.splunk").loadF[IO, SplunkConfig]())
      client     <- splunkConf match {
        case SplunkConfig.Disabled => Resource.pure[IO, Option[SplunkClient]](None)
        case SplunkConfig.Enabled(uri, username, password) => SplunkClient.make(uri, username, password).map(Some(_))
      }
    } yield client

    Stream.resource(splunkClient).flatMap {
      case Some(sclient: SplunkClient) =>
        val eventStream: Stream[IO, Event] = sclient.query()

        eventStream.filter{
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
      case None =>
        Stream.empty
    }
}