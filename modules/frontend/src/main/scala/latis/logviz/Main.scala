package latis.logviz

import calico.IOWebApp
import cats.effect.IO
import cats.effect.Resource
import fs2.dom.HtmlElement
import org.http4s.dom.FetchClientBuilder

object Main extends IOWebApp {

  override def render: Resource[IO, HtmlElement[IO]] = {
    val http = FetchClientBuilder[IO].create

    val events = EventClient
      .fromHttpClient(http)
      .flatMap(_.getEvents.compile.toList)

    events
      .map(EventComponent(_))
      .toResource
      .flatMap(_.render)
  }
}
