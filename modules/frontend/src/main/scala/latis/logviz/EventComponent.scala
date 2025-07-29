package latis.logviz

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter

import org.scalajs.dom
import org.scalajs.dom.HTMLElement
import org.scalajs.dom.HTMLCanvasElement
import org.scalajs.dom.MouseEvent
import calico.html.io.{*, given}
import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import cats.effect.std.Dispatcher
import cats.effect.std.PQueue
import cats.effect.kernel.Ref
import fs2.dom.HtmlElement
import fs2.Stream

import latis.logviz.model.Event
import latis.logviz.model.RequestEvent
import latis.logviz.model.Rectangle



/**
  * Draws canvas components with log event details
  * 
  * @param stream stream of log events from EventClient
  */
class EventComponent(stream: Stream[IO, Event], requestDetails: HtmlElement[IO]) {
  val timestampFormatter= DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:00")
  val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  val pixelsPerSec = 1.0

 
  def render: Resource[IO, HtmlElement[IO]] = 

    val inCompleteEventsRef: Resource[IO, Ref[IO, Map[String, (RequestEvent, Int)]]] =
      Resource.eval(Ref[IO].of(Map[String, (RequestEvent, Int)]()))
    val completeEventsRef: Resource[IO, Ref[IO, List[(RequestEvent, Int)]]] =
      Resource.eval(Ref[IO].of(List[(RequestEvent, Int)]()))
    val rectangleRef: Resource[IO, Ref[IO, List[Rectangle]]] =
      Resource.eval(Ref[IO].of(List[Rectangle]()))
    val colCounter: Resource[IO, Ref[IO, Int]] = 
      Resource.eval(Ref[IO].of(0))
    val colMax: Resource[IO, Ref[IO, Int]] = 
      Resource.eval(Ref[IO].of(0))
    val prevScrollPos: Resource[IO, Ref[IO, Double]] = 
      Resource.eval(Ref[IO].of(0.0))
    val liveRef: Resource[IO, Ref[IO, Boolean]] = 
      Resource.eval(Ref[IO].of(true))
    
    
    for {
      pq <- Resource.eval(PQueue.bounded[IO, Int](100)) //currently limiting number of columns to 100
      _ <- Resource.eval((0 to 99).toList.traverse(pq.offer(_)))
      eventsRef     <-  inCompleteEventsRef
      compEventsRef <-  completeEventsRef
      rectRef       <-  rectangleRef
      counter       <-  colCounter
      maxCounter    <-  colMax
      prevTop       <-  prevScrollPos
      live          <-  liveRef
      canvasIO      <-  canvasTag(idAttr:= "canvas")
      sizer         <-  div(idAttr:= "sizer")
      timeline      <-  div(idAttr:= "timeline", sizer, canvasIO)
      canvas        =   canvasIO.asInstanceOf[HTMLCanvasElement]
      context       =   canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D] 
      _             <-  Resource.eval(parse(eventsRef, compEventsRef, counter, maxCounter, pq))
      _             <-  animate(canvas, context, eventsRef, compEventsRef, rectRef, maxCounter, prevTop, live, sizer.asInstanceOf[HTMLElement])
      _             <-  hover(canvas, rectRef, requestDetails.asInstanceOf[HTMLElement])
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
  // https://typelevel.org/cats-effect/docs/std/pqueue
  def parse(eventsRef: Ref[IO, Map[String, (RequestEvent, Int)]], compEventsRef: Ref[IO, List[(RequestEvent, Int)]], counter: Ref[IO, Int], maxCounter: Ref[IO, Int], pq: PQueue[IO, Int]) =
    stream.evalTap{ event =>
      event match {
        // not possible for server (re)start to overlap with events? but need the currdepth for tuple requirement
        case Event.Start(time) => 
          for {
            cDepth  <-  pq.take
            c       <-  counter.updateAndGet(c => c + 1) 
            _       <-  maxCounter.update(prev => math.max(prev, c))
            _       <-  compEventsRef.update(lst => 
                          (RequestEvent.Server(time), cDepth) +: lst
                        )
            _       <- counter.update(c => c - 1)
            _       <- pq.offer(cDepth)

          } yield ()

        case Event.Request(id, time, request) => 
          for {
            cDepth  <-  pq.take
            c       <-  counter.updateAndGet(c => c + 1)
            _       <-  maxCounter.update(prev => math.max(prev, c))
            _       <-  eventsRef.update(map =>
                          map + (id -> (RequestEvent.Request(time, request), cDepth))
                        )
          } yield ()

        case Event.Response(id, time, status) => 
          if (status >= 400) {
            for {
              map     <-  eventsRef.get 
              cDepth  <-  map.get(id) match {
                            case Some(value) => value match {
                              case (RequestEvent.Request(start, url), currDepth) => 
                                for {
                                  _ <-  compEventsRef.update(lst =>
                                          (RequestEvent.Failure(start, url, time, status.toString()),
                                          currDepth) +: lst
                                        )
                                } yield (currDepth)
                              case _ => throw new IllegalArgumentException(
                                "Event found that is not request, something seriously wrong")
                            }
                            case None => throw new IllegalArgumentException(
                              "No event of this id found")
                      
                          }
              _       <-  eventsRef.update(m => m - id)
              _       <-  counter.update(c => c - 1)
              _       <-  pq.offer(cDepth)
            } yield()
          }
          else {
            IO.unit
          }

        case Event.Success(id, time, duration) => 
          for {
            map     <-  eventsRef.get
            cDepth  <-  map.get(id) match {
                          case Some(value) => value match {
                            case (RequestEvent.Request(start, url), currDepth) => 
                              for {
                                _ <-  compEventsRef.update(lst =>
                                        (RequestEvent.Success(start, url, time, duration),
                                        currDepth) +: lst
                                      )
                              } yield (currDepth)
                            case _ => throw new IllegalArgumentException(
                              "Event found that is not request, something went wrong")
                          }
                          case None => throw new IllegalArgumentException(
                            "No event found with this id")
                        }
            _       <-  eventsRef.update(m => m - id)
            _       <-  counter.update(c => c - 1)
            _       <-  pq.offer(cDepth)
          } yield()

        case Event.Failure(id, time, msg) => 
          for {
            ms      <-  eventsRef.get
            cDepth  <-  ms.get(id) match {
                          case Some(value) => value match {
                            case (RequestEvent.Request(start, url), currDepth) => 
                              for {
                                _ <-  compEventsRef.update(lst => 
                                        (RequestEvent.Failure(start, url, time, msg),
                                        currDepth) +: lst
                                      )
                              } yield (currDepth)
                            case _ => throw new IllegalArgumentException(
                              "Event found that is not request, something went wrong")
                          }
                          case None => throw new IllegalArgumentException(
                            "No event found with this id")
                        }
            _       <-  eventsRef.update(m => m - id)
            _       <-  counter.update(c => c - 1)
            _       <-  pq.offer(cDepth)
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


  def makeRect(
    current: LocalDateTime,
    eventsRef: Ref[IO, Map[String, (RequestEvent, Int)]],
    compEventsRef: Ref[IO, List[(RequestEvent, Int)]],
    rectRef: Ref[IO, List[Rectangle]],
    maxCounter: Ref[IO, Int],
    height: Double,
    top: Double,
    width: Int
  ) = 
    for {
      cols    <-  maxCounter.get
      cwidth  <-  IO(width/cols)
      _       <-  rectRef.set(List[Rectangle]())
      bottomY =   top + height
      _       <-  eventsRef.get.flatTap{ m =>
                    m.toList.traverse{ (_, event) =>
                      event match {
                        case (RequestEvent.Request(start, url), cDepth) => {
                          val startY = convertTime(LocalDateTime.parse(start, formatter), current)
                          val y = startY - top
                          if (startY > bottomY) {
                            rectRef.update(lst =>
                              Rectangle(event,
                              150 + cDepth * cwidth, height, cwidth, -height, "green") +: lst)
                          } else {
                            rectRef.update(lst => 
                              Rectangle(event,
                              150 + cDepth * cwidth, y, cwidth, -y, "green") +: lst)
                          }
                        }
                        case _ => throw new IllegalArgumentException(
                          "Any other RequestEvent type should not be an incomplete event")
                      }
                    }
                  } 

      _       <-  compEventsRef.get.flatTap{ events =>
                    events.traverse {
                      case (RequestEvent.Server(time), cDepth) => {
                        val startY = convertTime(LocalDateTime.parse(time, formatter), current)
                        val y = startY - top
                        if (startY <= bottomY) {
                          rectRef.update(lst => 
                            Rectangle((RequestEvent.Server(time), cDepth),
                            150 + cDepth * cwidth, y, cwidth, -1, "green") +: lst)
                        } else {
                          IO.unit
                        }
                      }
                      case (RequestEvent.Request(start, url), cDepth) => throw new IllegalArgumentException(
                        "Request event type should not be a completed event")
                      case (RequestEvent.Success(start, url, end, duration), cDepth) => {
                        val y = convertTime(LocalDateTime.parse(start, formatter), current)
                        val y_end = convertTime(LocalDateTime.parse(end, formatter), current)
                        if (y >= top && y_end < bottomY) {
                          rectRef.update(lst => 
                            Rectangle((RequestEvent.Success(start, url, end, duration), cDepth), 
                            150 + cDepth * cwidth, y-top, cwidth, y_end - y, "green") +: lst
                          )
                        } else {
                          IO.unit
                        }
                      }
                      case (RequestEvent.Failure(start, url, end, msg), cDepth) => {
                        val y = convertTime(LocalDateTime.parse(start, formatter), current)
                        val y_end = convertTime(LocalDateTime.parse(end, formatter), current)
                        if (y >= top && y_end < bottomY) {
                          rectRef.update(lst => 
                            Rectangle((RequestEvent.Failure(start, url, end, msg), cDepth),
                            150 + cDepth * cwidth, y-top, cwidth, y_end-y, "red") +: lst)

                        } else {
                          IO.unit
                        }
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
   * @param rectRef
   * @param maxCounter number of columns to draw
   * @param top   current scroll position from the top
   * @param width total available space for columns to be drawn
  */
  def drawCanvas(
    current: LocalDateTime,
    endTime: LocalDateTime,
    canvas: HTMLCanvasElement,
    context: dom.CanvasRenderingContext2D,
    rectRef: Ref[IO, List[Rectangle]],
    maxCounter: Ref[IO, Int],
    top: Double,
    width: Int
  ) =

    val topTS = current.minusSeconds((top / pixelsPerSec).toLong)
    val currTruncated = topTS.truncatedTo(ChronoUnit.MINUTES)
    val mins = ((math.min(convertTime(endTime, topTS), canvas.height.toDouble) /pixelsPerSec / 60)).toInt
    val colors = List("lightgray", "white")

    for {
      _     <-  IO(context.clearRect(0,0,canvas.width, canvas.height))
      cols  <-  maxCounter.get
      _     <-  (0 until cols).toList
                .traverse { col => 
                  val colWidth = width / cols
                  val x = 150 + colWidth * col
                  val color = colors(col % 2)
                  drawRect(context, x, 0, colWidth, canvas.height, color)
                }
      _     <-  (0 to mins).toList
                .traverse{ min =>
                  val timestamp = currTruncated.minusMinutes(min)
                  val y = convertTime(timestamp, topTS).toDouble
                  drawTS(context, y, timestamp)
                }
      _     <-  rectRef.get.flatTap{ rects => 
                  rects.traverse{
                    case Rectangle(event, x, y, width, height, color) => 
                    drawRect(context, x, y, width, height, color)
                  }

                }
    } yield()


  def drawRect(
    context: dom.CanvasRenderingContext2D,
    x: Double,
    y: Double,
    width: Double,
    duration: Double,
    color: String
  ) = IO {
    context.fillStyle = color
    context.fillRect(x, y, width, duration)
  }
  
  def drawTS(
    context: dom.CanvasRenderingContext2D,
    y: Double,
    timestamp: LocalDateTime)
  = IO {
    context.font = ("helvectica")
    context.fillStyle = "black"
    context.fillText(timestamp.format(timestampFormatter), 0, y)}

  /**
   * Animate canvas on the browser
   * 
   * (Re) creates list of rectangles to be drawn on canvas at each animation frame
   * 
   * Used dispatcher utility to use requestAnimationFrame as it takes in a js callback function. 
   * Dispatcher resource: [[https://typelevel.org/cats-effect/docs/std/dispatcher]]
  */
  def animate(
    canvas: HTMLCanvasElement,
    context: dom.CanvasRenderingContext2D,
    eventsRef: Ref[IO, Map[String, (RequestEvent, Int)]],
    compEventsRef: Ref[IO, List[(RequestEvent, Int)]],
    rectRef: Ref[IO, List[Rectangle]],
    maxCounter: Ref[IO, Int],
    prevScrollPos: Ref[IO, Double],
    liveRef: Ref[IO, Boolean],
    sizer: HTMLElement
  )=
    Dispatcher.sequential[IO] evalTap{ dispatcher => 

      def go(timestamp: Double): IO[Unit] = 
        for {
          top     <-  IO(canvas.parentElement.scrollTop)
          live    <-  liveRef.get
          prevTop <-  prevScrollPos.get
          // _       <-  IO.println(top)
          _       <-  if (live || prevTop != top) {
                        for {
                          tlRect  <-  IO(canvas.parentElement.getBoundingClientRect())
                          height  =   tlRect.height
                          _       <-  IO(canvas.width = tlRect.width.toInt)
                          _       <-  IO(canvas.height = tlRect.height.toInt)
                          width   <-  IO(canvas.width - 150)     // offset by 150 for total width of canvas that rectangles should cover
                          now     <-  IO(LocalDateTime.now(java.time.ZoneId.of("UTC")))
                          end     <-  IO(now.toLocalDate.atStartOfDay())
                          _       <-  IO(sizer.style.height = s"${convertTime(end, now)+2}px")
                          _       <-  makeRect(now, eventsRef, compEventsRef, rectRef, maxCounter, height, top, width)
                          _       <-  drawCanvas(now, end, canvas, context, rectRef, maxCounter, top, width)
                          _       <-  prevScrollPos.update(_ => top)
                          _       <-  if (top == 0.0) {
                                        liveRef.update(_ => true)
                                      } else {
                                        liveRef.update(_ => false)
                                      }
                        } yield ()
                      } else {
                        IO.unit
                      }
          _       <-  IO.delay(dom.window.requestAnimationFrame(ts => 
                        dispatcher.unsafeRunAndForget(go(ts))))
        } yield()

      IO.delay(dom.window.requestAnimationFrame(ts => 
        dispatcher.unsafeRunAndForget(go(ts))))
      }


  /** 
  * Displays event information on hover over an event rectangle
  *
  * @param canvas
  * @param rectRef
  * @param requestDetails HTML element to show event details of the event hovered over
  */
  def hover(canvas: HTMLCanvasElement, rectRef: Ref[IO, List[Rectangle]], requestDetails: HTMLElement) =
    Dispatcher.sequential[IO] evalTap { dispatcher =>
      IO(canvas.onmousemove = { (event: MouseEvent) =>
        // gets position of mouse click, subtract that by the canvas dimensions and we get the mouse positions relative to the canvas.
        val rect = canvas.getBoundingClientRect()
        val mouseX = event.clientX - rect.left
        val mouseY = event.clientY - rect.top

        def checkHover: IO[Unit] = {
          for {
            _ <-  rectRef.get.flatTap { rects =>
                    rects.traverse {
                      case Rectangle(event, x, y, width, height, color) => {
                        if (mouseX >= x && mouseX <= x + width && mouseY <= y && mouseY >= y + height) {
                          IO(requestDetails.textContent = s"EVENT DETAILS: $event")
                        } else {
                          IO.unit
                        }
                      }
                    }
                  }
          } yield ()
        }
        dispatcher.unsafeRunAndForget(checkHover)
      })
    }
}

