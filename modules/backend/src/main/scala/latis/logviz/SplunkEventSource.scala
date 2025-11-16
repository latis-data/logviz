package latis.logviz

import java.time.LocalDateTime

import cats.effect.IO
import fs2.Stream

import latis.logviz.model.Event
import latis.logviz.splunk.*

/** An event source that reads events from Splunk
 * 
 * @constructor create a new splunk source with a splunk client
 * @param sclient the SplunkClient used to access splunk
 */
class SplunkEventSource(sclient: SplunkClient, source: String, index: String) extends EventSource {
  override def getEvents(start: LocalDateTime, end: LocalDateTime): Stream[IO, Event] = {
    sclient.query(start, end, source, index)
  }
}