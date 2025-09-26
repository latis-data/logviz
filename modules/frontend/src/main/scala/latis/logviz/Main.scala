package latis.logviz

import java.time.LocalDateTime
import java.time.ZoneOffset

import calico.IOWebApp
import calico.html.io.{*, given}
import cats.effect.IO
import cats.effect.Resource
import fs2.dom.HtmlElement
import fs2.concurrent.SignallingRef
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
      // header      <- div(idAttr:= "header")
      requestH1   <- h1(idAttr:= "request")
      // bottom      <- div(idAttr:= "bottom-panel", div(idAttr:= "request-detail", requestH1))
      now         <- Resource.eval(IO(LocalDateTime.now(ZoneOffset.UTC)))
      startRef    <- Resource.eval(SignallingRef[IO].of(now.toLocalDate().atStartOfDay()))
      endRef      <- Resource.eval(SignallingRef[IO].of(now))
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
                          _ <- endRef.set(LocalDateTime.now(ZoneOffset.UTC))
                        } yield ()
                      })
                    )
      timeRange   <- new TimeRangeComponent(startRef, endRef, liveRef).render
      component   =  new EventComponent(ec.getEvents, requestH1, startRef, endRef, liveRef)
      timeline    <- component.render
      timeSelect  <- div(idAttr:= "time-selection", liveButton, timeRange)
      testBox     <- div(idAttr:= "test-box")
      requestInfo <- div(idAttr:= "request-detail", requestH1)
      box         <- div(idAttr:= "box", timeline, timeSelect, testBox, requestInfo)
      html        <- div(idAttr:= "container", box)
      
  } yield html
}
 
}
