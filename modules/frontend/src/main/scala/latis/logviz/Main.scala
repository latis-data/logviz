package latis.logviz

import calico.IOWebApp
import cats.effect.IO
import cats.effect.Resource
import fs2.dom.HtmlElement
import org.http4s.dom.FetchClientBuilder
import org.http4s.client.Client
import latis.logviz.model.Event

/**
 * Renders HTML elements for log events using EventClient and EventComponent
 * 
 * Using the events that we get back from decoding events.json through EventClient,
 * events gets passed to EventComponent to get HTML element for events. 
 * */ 
object Main extends IOWebApp {

  override def render: Resource[IO, HtmlElement[IO]] = {
    val http: Client[IO] = FetchClientBuilder[IO].create

    val events: IO[List[Event]] = EventClient
      .fromHttpClient(http)
      .flatMap(_.getEvents.compile.toList)

    events
      .map(EventComponent(_))
      .toResource
      .flatMap(_.render)
  }
}
