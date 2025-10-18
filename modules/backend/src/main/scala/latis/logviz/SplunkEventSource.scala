package latis.logviz

import java.time.LocalDateTime

import cats.effect.IO
import fs2.Stream

import latis.logviz.model.Event
import latis.logviz.splunk.*

class SplunkEventSource(sclient: SplunkClient, source: String, index: String) extends EventSource {
  override def getEvents(start: LocalDateTime, end: LocalDateTime): Stream[IO, Event] = {
    sclient.query(start, end, source, index)
  }
}