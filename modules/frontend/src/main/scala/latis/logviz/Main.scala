package latis.logviz

import calico.IOWebApp
import calico.html.io.{*, given}
import cats.effect.IO
import cats.effect.Resource
import fs2.dom.HtmlElement
import org.http4s.dom.FetchClientBuilder
import org.http4s.client.Client


/**
 * Renders HTML elements for log events using EventClient and EventComponent
 * 
 * set up the layout for logviz page with header, canvas and bottom panel
 * passing stream of events from eventclient to EventComponent to parse, store and draw onto canvas
 * */ 
object Main extends IOWebApp {

  override def render: Resource[IO, HtmlElement[IO]] = {
    val http: Client[IO] = FetchClientBuilder[IO].create

    val client: Resource[IO, EventClient] = Resource.eval(EventClient.fromHttpClient(http))

    for {
      ec          <- client
      header      <- div(idAttr:= "header")
      requestH1   <- h1(idAttr:= "request")
      bottom      <- div(idAttr:= "bottom-panel", div(idAttr:= "request-detail", requestH1))
      component   =  new EventComponent(ec.getEvents, requestH1)
      timeline    <- component.render
      html        <- div(idAttr:= "container", header, timeline, bottom)
      
  } yield html
}
 
}
