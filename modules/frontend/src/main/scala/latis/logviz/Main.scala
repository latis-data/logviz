package latis.logviz

import calico.IOWebApp
import cats.effect.IO
import cats.effect.Resource
import fs2.dom.HtmlElement
import org.http4s.dom.FetchClientBuilder
import org.http4s.client.Client
import latis.logviz.model.Event
import calico.html.io.{*, given}
import latis.logviz.model.RequestEvent
import latis.logviz.model.Rectangle
import cats.effect.kernel.Ref


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
    //will want to move the creation of these refs somewhere else to keep everything clean
    val inCompleteEventsRef: Resource[IO, Ref[IO, Map[String, RequestEvent]]] = Resource.eval(Ref[IO].of(Map[String, RequestEvent]()))
    val completeEventsRef: Resource[IO, Ref[IO, List[RequestEvent]]] = Resource.eval(Ref[IO].of(List[RequestEvent]()))
    val rectangleRef: Resource[IO, Ref[IO, List[Rectangle]]] = Resource.eval(Ref[IO].of(List[Rectangle]()))
    for {
      ec          <- client
      incompRef   <- inCompleteEventsRef
      compRef     <- completeEventsRef
      rectRef     <- rectangleRef
      header      <- div(idAttr:= "header")
      requestH1   <- h1(idAttr:= "request")
      bottom      <- div(idAttr:= "bottom-panel", div(idAttr:= "request-detail", requestH1))
      component   =  new EventComponent(ec.getEvents, incompRef, compRef, rectRef)
      timeline    <- component.render
      html      <- div(idAttr:= "container", header, timeline, bottom)
      
  } yield html
}
 
}
