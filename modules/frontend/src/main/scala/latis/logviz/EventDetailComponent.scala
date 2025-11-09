package latis.logviz

import calico.html.io.*
import calico.html.io.given
import cats.effect.IO
import cats.effect.Resource
import fs2.concurrent.Signal
import fs2.dom.*

case class EventDetails(event: String, start: String, end: String, duration: String, url: String)

/**
  * Given a signallingRef, will take the EventDetails out of the signal 
  * and extract values from the case class to put in a div element that
  * will display the given event's details when hovering
  * 
  * @param eventDetails a signal containing the value of the event when hovering over a given rectangle
  */
class EventDetailComponent(eventDetails: Signal[IO, Option[EventDetails]]) {
  def render: Resource[IO, HtmlElement[IO]] = {
    
    IO.pure(eventDetails).toResource.flatMap { details =>
      val event: Signal[IO, EventDetails] = details.map {
        case Some(event) => event
        case None => new EventDetails("none", "none", "none", "none", "none")
      }
      div(
        event.map { details => 
          span(b("Event: "), details.event, br(()),
            b("Start: "), details.start, br(()),
            b("End: "), details.end, br(()),
            b("Duration: "), details.duration, br(()),
            b("Path: "), details.url
          )
        }
      )
    }
  }
}