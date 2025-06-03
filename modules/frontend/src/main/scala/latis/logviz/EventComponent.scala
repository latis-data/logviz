package latis.logviz

import calico.html.io.{*, given}
import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import fs2.dom.HtmlElement

import latis.logviz.model.Event

class EventComponent(events: List[Event]) {
  def render: Resource[IO, HtmlElement[IO]] =
    div(
      events.traverse {
        case Event.Hello => p("hello")
        case Event.World => p("world")
      }
    )
}
