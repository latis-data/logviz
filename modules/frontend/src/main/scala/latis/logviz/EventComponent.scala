package latis.logviz

import calico.html.io.{*, given}
import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import fs2.dom.HtmlElement
import latis.logviz.model.Event
import latis.logviz.model.RequestEvent
import fs2.Stream

import org.scalajs.dom
import org.scalajs.dom.HTMLCanvasElement
import cats.effect.std.Dispatcher
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import cats.effect.kernel.Ref
import latis.logviz.model.Rectangle



/**
  * Draws canvas components with log event details
  * 
  * @param stream stream of log events from EventClient
  * @param eventsRef mapping of incomplete events to their id. id used to identify corresponding log events coming in from stream
  * @param compEventsRef list of completed events with completed details
  * @param rectRef list of rectangles to be drawn
  */
class EventComponent(stream: Stream[IO, Event], eventsRef: Ref[IO, Map[String, RequestEvent]], compEventsRef: Ref[IO, List[RequestEvent]], rectRef: Ref[IO, List[Rectangle]]) {
  val timestampFormatter= java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:00") //couldnt get timezone error
  val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  val pixelsPerSec = 1.0 //turns out I was using integer division before so I was getting value of 0. 

 
  def render: Resource[IO, HtmlElement[IO]] = 
    // canvas.flatMap{ c =>
    //   div(c).evalTap{ t =>
    //     val test = t.asInstanceOf[HTMLCanvasElement]
    //     val context = test.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]

    //     for {
    //       _   <- Resource.eval(parse)
    //       _   <- animate(context)
    //       _   <- hover(test)

    //     } yield()
    //   }
    // }
    
    for {
      canvasIO  <-  canvasTag(idAttr:= "canvas", widthAttr:= 1246, heightAttr:= 10000)
      canvas    =   canvasIO.asInstanceOf[HTMLCanvasElement]
      context   =   canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D] 
      _         <-  Resource.eval(parse)
      _         <-  animate(canvas, context)
      timeline  <-  div(idAttr:= "timeline", canvasIO)
    } yield(timeline)


  /**
   * Parsing though log events from stream and mapping/appending (in)complete events
   * 
   * For each event in stream
   *  - Start: since start has no id and is waiting on no other events, will append to list of completed events
   *  - Request: is an incompleted event, waiting on additional log event(s) of same id
   *  - Response: If we get an error status code, we have a completed event: remove mapping and add to list of completed events. Else, do nothing as we have successful response code so we will wait for an outcome log event.
   *  - Success: Successful request event. Remove from map and append to list of completed events
   *  - Failure: Failed request event. Remove from map and append to list of completed events
  */
  def parse =
    stream.evalTap{event =>
      event match {
        case Event.Start(time) => compEventsRef.update(lst => RequestEvent.Server(time) +: lst)

        case Event.Request(id, time, request) => eventsRef.update(ms => ms + (id -> RequestEvent.Request(time, request)))
        case Event.Response(id, time, status) => 
          if (status >= 400) {
            for {
              ms <- eventsRef.get
              _  <- ms.get(id) match {
                      case Some(value) => value match {
                        case RequestEvent.Request(start, url) => compEventsRef.update(lst => RequestEvent.Failure(start, url, time, status.toString()) +: lst)
                        case _ => throw new IllegalArgumentException("Event found that is not request, something seriously wrong")
                      }
                      case None => throw new IllegalArgumentException("No event of this id found")
                      
                    }
              _   <- eventsRef.update(m => m - id)
            } yield()
          }
          else {
            IO.unit
          }

        case Event.Success(id, time, duration) => 
          for {
            ms  <- eventsRef.get
            _   <- ms.get(id) match {
                      case Some(value) => value match {
                        case RequestEvent.Request(start, url) => compEventsRef.update(lst => RequestEvent.Success(start, url, time, duration) +: lst)
                        case _ => throw new IllegalArgumentException("Event found that is not request, something went wrong")
                      }
                      case None => throw new IllegalArgumentException("No event found with this id")
                    }
            _   <- eventsRef.update(m => m - id)
          } yield()

        case Event.Failure(id, time, msg) => 
          for {
            ms  <- eventsRef.get
            _   <- ms.get(id) match {
                      case Some(value) => value match {
                        case RequestEvent.Request(start, url) => compEventsRef.update(lst => RequestEvent.Failure(start, url, time, msg) +: lst)
                        case _ => throw new IllegalArgumentException("Event found that is not request, something went wrong")
                      }
                      case None => throw new IllegalArgumentException("No event found with this id")
                    }
            _   <- eventsRef.update(m => m - id)
          } yield()
      }
    }.compile.drain

  
  /**
   * Conversion to get position of a timestamp on the canvas
   * 
   * Find the difference in seconds between the timestamp we're interested in and the current time
   * Multiply by abitrary pixels per second to get y position on the canvas to start drawing on. 
   * Same as saying how far off we are from the top of canvas(current time)
   * [[https://www.geeksforgeeks.org/java/java-time-duration-class-in-java/#]]
  */
  def convertTime(timestamp: LocalDateTime, current: LocalDateTime) = 
    java.time.Duration.between(timestamp, current).toSeconds() * pixelsPerSec


  def makeRect(current: LocalDateTime) = 
    for {
      _ <- rectRef.set(List[Rectangle]())
      _ <- eventsRef.get.flatTap{ m =>
              m.toList.traverse{ (_, event) =>
                event match {
                  case RequestEvent.Request(start, url) => {
                    val y = convertTime(LocalDateTime.parse(start, formatter), current)
                    rectRef.update(lst => Rectangle(event, 150, y, 200, -y, "green") +: lst)
                    }
                  case _ => throw new IllegalArgumentException("Any other RequestEvent type should not be an incomplete event")
                }
              }
            } 

      _  <- compEventsRef.get.flatTap{ events =>
              // println(events)
              events.traverse {
                case RequestEvent.Server(time) => {
                  val y = convertTime(LocalDateTime.parse(time, formatter), current)
                  rectRef.update(lst => Rectangle(RequestEvent.Server(time), 150, y, 200, -1, "green") +: lst)
                  }
                case RequestEvent.Request(start, url) => throw new IllegalArgumentException("Request event type should not be a completed event")
                case RequestEvent.Success(start, url, end, duration) => {
                  val y = convertTime(LocalDateTime.parse(start, formatter), current)
                  val y_end = convertTime(LocalDateTime.parse(end, formatter), current)
                  rectRef.update(lst => Rectangle(RequestEvent.Success(start, url, end, duration), 150, y, 200, y_end-y, "green") +: lst)
                  }
                case RequestEvent.Failure(start, url, end, msg) => {
                  val y = convertTime(LocalDateTime.parse(start, formatter), current)
                  val y_end = convertTime(LocalDateTime.parse(end, formatter), current)
                  rectRef.update(lst => Rectangle(RequestEvent.Failure(start, url, end, msg), 150, y, 200, y_end-y, "red") +: lst)
                  }
              }
            }

    }yield()

  /**
   * (Re)Drawing canvas timestamp ticks and event rectangles
   * 
   * Truncate current time to minutes. Needed for drawing only 0 second timestamps
   * Resource for truncate: [[https://www.geeksforgeeks.org/java/localtime-truncatedto-method-in-java-with-examples/]]
   * Got difference in minutes between current time and endtime to determine number of timestamp drawings
   * 
   * For every minute: calculate its position and draw that timestamp onto canvas
   * Draw every rectangle event from list of rectangles
   * 
   * @param current current time to be used for convertTime function
   * @param endTime the end of range of time that user may want to see
   * @param canvas
   * @param context 
  */
  def drawCanvas(current: LocalDateTime, endTime: LocalDateTime, canvas: HTMLCanvasElement, context: dom.CanvasRenderingContext2D) =
    context.clearRect(0,0,canvas.width, canvas.height)

    val currTruncated = current.truncatedTo(ChronoUnit.MINUTES)
    val mins = java.time.Duration.between(endTime, current).toMinutes.toInt

    for {
      _  <- (0 to mins) 
              .toList
              .traverse{ min =>
                val timestamp = currTruncated.minusMinutes(min)
                val y = convertTime(timestamp, current).toDouble
                // println(y)
                // println(timestamp)
                drawTS(context, y, timestamp)
              }
      _  <- rectRef.get.flatTap{ rects => 
              rects.traverse{
                case Rectangle(event, x, y, width, height, color) => drawRect(context, x, y, width, height, color)
              }

            }
      // _  <- eventsRef.get.flatTap{ m =>
      //         m.toList.traverse{ (_, event) =>
      //           event match {
      //             case RequestEvent.Request(start, url) => {
      //               val y = convertTime(LocalDateTime.parse(start, formatter), current)
      //               // drawLine(150, y, 200, "green")}
      //               drawRect(context, 150, y, 200, -y, "green")}
      //             case _ => throw new IllegalArgumentException("Any other RequestEvent type should not be an incomplete event")
      //           }
      //         }
      //       }
      // _  <- compEventsRef.get.flatTap{ events =>
      //         // println(events)
      //         events.traverse {
      //           case RequestEvent.Server(time) => {
      //             val y = convertTime(LocalDateTime.parse(time, formatter), current)
      //             drawRect(context, 150, y, 200, 2, "green") }
      //           case RequestEvent.Request(start, url) => throw new IllegalArgumentException("Request event type should not be a completed event")
      //           case RequestEvent.Success(start, url, end, duration) => { //now that I am not using duration, need to figure out what to do with it
      //             val y = convertTime(LocalDateTime.parse(start, formatter), current)
      //             val y_end = convertTime(LocalDateTime.parse(end, formatter), current)
      //             drawRect(context, 150, y, 200, y_end-y, "green")}
      //           case RequestEvent.Failure(start, url, end, msg) => {
      //             val y = convertTime(LocalDateTime.parse(start, formatter), current)
      //             val y_end = convertTime(LocalDateTime.parse(end, formatter), current)
      //             drawRect(context, 150, y, 200, y_end-y, "red")}
      //         }
      //       }
    } yield()


  def drawRect(context: dom.CanvasRenderingContext2D, x: Double, y: Double, width: Double, duration: Double, color: String) = IO {
    context.fillStyle = color
    context.fillRect(x, y, width, duration)
  }
  
  def drawTS(context: dom.CanvasRenderingContext2D, y: Double, timestamp: LocalDateTime) = IO {
    context.font = ("helvectica")
    context.fillStyle = "black"
    context.fillText(timestamp.format(timestampFormatter), 0, y)}

  /**
   * Animate canvas on the browser
   * 
   * Used dispatcher utility to use requestAnimationFrame as it takes in a js callback function. 
   * Within our function, we call go which gets the current time, redraws canvas and calls requestAnimationFrame on itself to update frame
   * Dispatcher resource: [[https://typelevel.org/cats-effect/docs/std/dispatcher]]
  */
  def animate(canvas: HTMLCanvasElement, context: dom.CanvasRenderingContext2D) =
    Dispatcher.sequential[IO] evalTap{ dispatcher => 
      def go(timestamp: Double): IO[Unit] = 
        for {
          now <- IO(LocalDateTime.now(java.time.ZoneId.of("UTC"))) // for some reason local time zone doesnt work? America/Denver
          end <- IO(now.toLocalDate.atStartOfDay()) //eventually take in user input to set the time range? default to same date start of day
          _   <- makeRect(now)
          _   <- drawCanvas(now, end, canvas, context)
          _   <- IO.delay(dom.window.requestAnimationFrame(ts => dispatcher.unsafeRunAndForget(go(ts))))
        } yield()

      IO.delay(dom.window.requestAnimationFrame(ts => dispatcher.unsafeRunAndForget(go(ts))))
      }

}

