package latis.logviz

import calico.html.io.{*, given}
import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import fs2.dom.HtmlElement

import latis.logviz.model.Event

/**
  * Renders HTML component with log event details
  * 
  * @param events list of (log) events from EventClient
  */
class EventComponent(events: List[Event]) {
  /**
   * Matches each event and displays information with HTML element
   * 
   * For each event, match the type of event. (Start, Request, Response, Success, Failure)
   * 
   * Display each event's information with a p tag. 
  */
  def render: Resource[IO, HtmlElement[IO]] =
    div(
      events.traverse {
        case Event.Start(t)       => p(s"Server (re)started at time $t")
        case Event.Request(t, r)  => p(s"Request of $r at time $t")
        case Event.Response(t, s) => p(s"Response with status code $s at time $t")
        case Event.Success(t, d)  => p(s"Outcome success at time $t. Total time took: $d")
        case Event.Failure(t, m)  => p(s"Outcome failure at time $t. Message: $m")
      }
    )
}
