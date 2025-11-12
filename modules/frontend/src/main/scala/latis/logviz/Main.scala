package latis.logviz

import java.time.LocalDateTime
import java.time.ZoneOffset

import calico.IOWebApp
import calico.html.io.{*, given}
import cats.effect.IO
import cats.effect.Resource
import cats.effect.kernel.Ref
import fs2.concurrent.SignallingRef
import fs2.dom.HtmlElement
import org.http4s.dom.FetchClientBuilder
import org.http4s.client.Client



/**
 * Renders HTML elements for log events using EventClient and EventComponent
 * 
 * set up the layout for logviz page 
 * passing stream of events from eventclient to EventComponent to parse, store and draw onto canvas
 * */ 
object Main extends IOWebApp {

  override def render: Resource[IO, HtmlElement[IO]] = {
    val http: Client[IO] = FetchClientBuilder[IO].create

    val client: Resource[IO, EventClient] = Resource.eval(EventClient.fromHttpClient(http))

    for {
      ec          <- client
      eventRef    <- Resource.eval(SignallingRef[IO].of[Option[EventDetails]](None))
      now         <- Resource.eval(IO(LocalDateTime.now(ZoneOffset.UTC)))
      startRef    <- Resource.eval(SignallingRef[IO].of(now.minusHours(24)))
      //***
      endRef      <- Resource.eval(SignallingRef[IO].of(now))
      liveRef     <- Resource.eval(SignallingRef[IO].of(true))
      //button to toggle whether to allow live updating or not.
      liveButton  <- button( 
                      `type` := "button",
                      "LIVE",
                      styleAttr <-- liveRef.map{
                                      case true => "background-color: #db2a30"
                                      case false => "background-color: #FFFFFF"
                      },
                      onClick(_ => {
                        for {
                          _ <- liveRef.update(bool => !bool)
                          _ <- endRef.set(LocalDateTime.now(ZoneOffset.UTC))
                        } yield ()
                      })
                    )
      //***
      //TODO: use later once able to make event request everytime time range changes
      //changes that are currently unused due to waiting on eventComponent rework will be marked with ***
      timeRange   <- new TimeRangeComponent(startRef, endRef, liveRef).render
      // timeSelect  <- div(idAttr:= "time-selection", liveButton, timeRange)
      //***

      //zoom ref might not need to be a signallingref since we're checking every animation frame
      zoomRef     <- Resource.eval(Ref[IO].of(1.0))
      zoom        <- new ZoomComponent(zoomRef).render

      evComponent =  new EventDetailComponent(eventRef)
      info        <- evComponent.render
      component   =  new EventComponent(ec.getEvents, eventRef, startRef, endRef, liveRef, zoomRef)
      timeline    <- component.render

      requestInfo <- div(idAttr:= "request-detail", info)
      box         <- div(idAttr:= "box", timeline, zoom, requestInfo)
      html        <- div(idAttr:= "container", box)
      
  } yield html
}
 
}
