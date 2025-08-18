package latis.logviz

import java.time.ZonedDateTime
import java.time.ZoneId

import calico.IOWebApp
import calico.html.io.{*, given}
import cats.effect.IO
import cats.effect.Resource
import fs2.dom.HtmlElement
import org.http4s.dom.FetchClientBuilder
import org.http4s.client.Client
import fs2.concurrent.SignallingRef


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
      requestH1   <- h1(idAttr:= "request")
      now         <- Resource.eval(IO(ZonedDateTime.now(java.time.ZoneId.of("UTC"))))
      endRef      <- Resource.eval(SignallingRef[IO].of(now))
      startRef    <- Resource.eval(SignallingRef[IO].of(now.toLocalDate.atStartOfDay(now.getZone())))
      liveRef     <- Resource.eval(SignallingRef[IO].of(true))
      liveButton  <- button(
                      `type` := "button",
                      "LIVE",
                      styleAttr <-- liveRef.map(bool => 
                                      bool match
                                        case true => "background-color: #db2a30"
                                        case false => "background-color: #FFFFFF"
                                    ),
                      onClick(_ => {
                        for {
                          _ <- liveRef.update(bool => !bool)
                          _ <- endRef.set(ZonedDateTime.now(ZoneId.of("UTC")))
                        } yield ()
                      })
                    )
      timeRange   <- new TimeRangeComponent(startRef, endRef, liveRef).render
      header      <- div(idAttr:= "header", liveButton)
      bottom      <- div(idAttr:= "bottom-panel", div(idAttr:= "request-detail", requestH1, timeRange))
      component   =  new EventComponent(ec.getEvents, requestH1, startRef, endRef, liveRef)
      timeline    <- component.render
      html        <- div(idAttr:= "container", header, timeline, bottom)
      
  } yield html
}
 
}
