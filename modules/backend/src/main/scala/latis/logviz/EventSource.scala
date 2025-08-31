package latis.logviz

import java.time.LocalDateTime

import cats.effect.IO
import fs2.Stream

import latis.logviz.model.Event

trait EventSource {
  def getEvents(start: LocalDateTime, end: LocalDateTime): Stream[IO, Event]
}