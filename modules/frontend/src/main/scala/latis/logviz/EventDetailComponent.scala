package latis.logviz

import calico.html.io.*
import calico.html.io.given
import cats.effect.IO
import cats.effect.Resource
import fs2.concurrent.Signal
import fs2.dom.*

/**
  * Given a signallingRef, will take the EventDetails out of the signal 
  * and extract values from the case class to put in a div element that
  * will display the given event's details when hovering
  * 
  * @param eventDetails a signal containing the value of the event when hovering over a given rectangle
  */
class EventDetailComponent(eventDetails: Signal[IO, Option[EventDetails]]) {
  def render: Resource[IO, HtmlElement[IO]] = {
    
    val event: Signal[IO, EventDetails] = eventDetails.map {
      _.getOrElse(EventDetails("none", "none", "none", "none", "none"))
    }

    div(
      event.map { details => 
        dl(
          cls := "request-detail-list",
          dt(
            cls := "request-detail-key",
            "Event"
          ),
          dd(details.event),
          dt(
            cls := "request-detail-key",
            "Start"
          ),
          dd(details.start),
          dt(
            cls := "request-detail-key",
            "End"
          ),
          dd(details.end),
          dt(
            cls := "request-detail-key",
            "Duration"
          ),
          dd(details.duration),
          dt(
            cls := "request-detail-key",
            "Path"
          ),
          dd(details.url),
        )
      }
    )
  }
}