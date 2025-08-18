package latis.logviz

import java.time.ZonedDateTime
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
import cats.effect.kernel.Ref
import fs2.concurrent.SignallingRef
import fs2.dom.HtmlElement
import fs2.Stream

import latis.logviz.model.Event
import latis.logviz.model.Rectangle



/**
  * Draws canvas components with log event details
  * 
  * @param stream stream of log events from EventClient
  * @param requestDetails div for hover feature
  */
class EventComponent(
  stream: Stream[IO, Event],
  requestDetails: HtmlElement[IO],
  startTime: SignallingRef[IO, ZonedDateTime],
  endTime: SignallingRef[IO, ZonedDateTime],
  liveRef: SignallingRef[IO, Boolean]
  ) {
  val timestampFormatter= DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:00 VV")
  val pixelsPerSec = 1.0

 
  def render: Resource[IO, HtmlElement[IO]] = 
    
    for {
      canvasIO  <- canvasTag(idAttr:= "canvas")
      sizer     <- div(idAttr:= "sizer")
      timeline  <- div(idAttr:= "timeline", sizer, canvasIO)
      canvas    =  canvasIO.asInstanceOf[HTMLCanvasElement]
      context   =  canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
      eParser   <- Resource.eval(EventParser())
      _         <- Resource.eval(stream.evalTap(event =>
                    eParser.parse(event)).compile.drain)
      scrollRef <- Resource.eval(Ref[IO].of(0.0))
      isLive    <- Resource.eval(Ref[IO].of(true))
      rectRef   <- Resource.eval(Ref[IO].of(List[Rectangle]()))
      end       <- Resource.eval(endTime.get)
      prevEndRef<- Resource.eval(Ref[IO].of(end))
      _         <- animate(canvas, context, sizer.asInstanceOf[HTMLElement], eParser, scrollRef, isLive, rectRef, prevEndRef)
      _         <- hover(canvas, requestDetails.asInstanceOf[HTMLElement], rectRef)
    } yield(timeline)
  
  /**
   * Conversion to get position of a timestamp on the canvas
   * 
   * Find the difference in seconds between the timestamp we're interested in and the current time
   * Multiply by abitrary pixels per second to get y position on the canvas to start drawing on. 
   * Same as saying how far off we are from the top of canvas(current time)
   * [[https://www.geeksforgeeks.org/java/java-time-duration-class-in-java/#]]
  */
  private def convertTime(timestamp: ZonedDateTime, current: ZonedDateTime): Double = 
    java.time.Duration.between(timestamp, current).toSeconds() * pixelsPerSec

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
   * @param rects list of rectangles to draw
   * @param cols number of columns to draw
   * @param top   current scroll position from the top
   * @param width total available space for columns to be drawn
  */
  private def drawCanvas(
    current: ZonedDateTime,
    startTime: ZonedDateTime,
    canvas: HTMLCanvasElement,
    context: dom.CanvasRenderingContext2D,
    rects: List[Rectangle],
    cols: Int,
    top: Double,
    width: Int
  ): IO[Unit] =

    val topTS = current.minusSeconds((top / pixelsPerSec).toLong)
    val currTruncated = topTS.truncatedTo(ChronoUnit.MINUTES)
    val mins = ((math.min(convertTime(startTime, topTS), canvas.height.toDouble) /pixelsPerSec / 60)).toInt
    val colors = List("lightgray", "white")

    for {
      _ <- IO(context.clearRect(0,0,canvas.width, canvas.height))
      _ <- (0 until cols).toList
            .traverse { col => 
              val colWidth = width / cols
              val x = 200 + colWidth * col
              val color = colors(col % 2)
              drawRect(context, x, 0, colWidth, canvas.height, color)
            }
      _ <- (0 to mins).toList
            .traverse{ min =>
              val timestamp = currTruncated.minusMinutes(min)
              val y = convertTime(timestamp, topTS).toDouble
              drawTS(context, y, timestamp)
            }
      _ <- rects.traverse{
            case Rectangle(event, x, y, width, height, color) => 
              drawRect(context, x, y, width, height, color)
            }
    } yield()


  private def drawRect(
    context: dom.CanvasRenderingContext2D,
    x: Double,
    y: Double,
    width: Double,
    duration: Double,
    color: String
  ): IO[Unit] = IO {
    context.fillStyle = color
    context.fillRect(x, y, width, duration)
  }
  
  private def drawTS(
    context: dom.CanvasRenderingContext2D,
    y: Double,
    timestamp: ZonedDateTime
    ): IO[Unit] = IO {
    context.font = ("15px helvectica")
    context.fillStyle = "black"
    context.fillText(timestamp.format(timestampFormatter), 0, y)}

  /**
   * Animate canvas on the browser
   * 
   * (Re) creates list of rectangles to be drawn on canvas at each animation frame
   * 
   * Used dispatcher utility to use requestAnimationFrame as it takes in a js callback function. 
   * Dispatcher resource: [[https://typelevel.org/cats-effect/docs/std/dispatcher]]
   * 
   * @param canvas
   * @param context
   * @param sizer div for controlling how much space we have to scroll
   * @param parser event parser holding list of events
   * @param prevScrollPos ref holding the previous scroll position
   * @param isLive ref determining whether to update live or not
   * @param rectRef ref to hold list of rectangles to be drawn
  */
  private def animate(
    canvas: HTMLCanvasElement,
    context: dom.CanvasRenderingContext2D,
    sizer: HTMLElement,
    parser: EventParser,
    prevScrollPos: Ref[IO, Double],
    isLive: Ref[IO, Boolean],
    rectRef: Ref[IO, List[Rectangle]],
    prevEndRef: Ref[IO, ZonedDateTime]
  ): Resource[IO, Dispatcher[IO]] =
    Dispatcher.sequential[IO] evalTap{ dispatcher => 

      def go(timestamp: Double): IO[Unit] = 
        //live scrolling breaks with timerange inputs, if we initially update time range, but since scroll is at 0, live goes back to true
        //I think I want to keep this feature, it makes sense to return to live scrolling if Im still looking at time close to current time

        //SOL 1:
        //initial: live = true, top = 0, prevTop == top/false
        //if (live || top == 0 || prevTop != top)
        // live = true || top = 0 || prevTop == top/false
        
        // scenarios: 
        // toggle button live = false top = 0 prevTop == top/false, //isnt a problem, but since I want the feature where if we scroll to the top then we resume live, I dont want this feature to happen if we toggle live to false. It also doesnt really look very good when I change the date range and if I accidentally scroll to the top, the transition seems off, especially if the end time input is much farther from the current time
        // 
        // 

        /** SOL 2: separate if conditions?
        // initial: live = true, prevTop == top/false top = 0
        // if (live) {
              draw, 
              if top != 0 then live = false

            } else {
              if prevTop != top{
                draw,
                if top == 0, then live = true
              }
              
              }

        // live = true, 
        //scenario: toggle button live = false, then we should check if top != false. So everytime we scroll, we draw, update live if it goes back up. same issue
        **/

        /** SOL 3: problem is that we could have scenarios where we are "live", but we're not visually seeing that if we're scrolled, until we scroll to the top
         * but this scroll to the top for live resume should only be allowed when we've pressed live button
         * So what if we have two refs for live. One for the toggle and one for the isLive which helped determine the scroll to live feature
         * 
         * initial: livetoggle = true, islive = true, top = 0, prevtop ==top-false
         * if (livetoggle){
         *  set end time to current
         *  draw with original implementation, if (isLive || prevTop != top) and set isLive depending on top
         * } else { 
            set end time to the endtime
            draw without isLive: if (prevTop != top || endTime != prevEndtime) draw
          
          }
         * 
         * 
         * scenarios- livetoggle = true
         *  - if we scroll, then we go into the if condition intially since prevtop != top, set isLive to false, and now prevTop should == top once we stop scrolling, so even though livetoggle is true, we shouldnt see any drawings since we wont go into the condition. 
         *      :possible problem: should livetoggle go to false if we stopped redrawing? but then we will go into other condition which doesnt go back to live once we scroll to top
         *  - 
         * scenarios- livetoggle = false
         *  - press live toggle button = false, so set endtime(initially current time)- initially shouldnt redraw since prevTop != top, but that should be fine since we're coming from a livetoggle = true so previous frame should be fine
         *  - set endTime range: toggle button false either from being false or true before. BIG PROBLEM: I believe it wont update until we move scroll. 
         *      -sol: have a prevEndTime ref and if that changes we redraw. 
        */
        //scroll to top when live?
        //update the value that we see in end input?

        for {
          liveTog <- liveRef.get
          top     <- IO(canvas.parentElement.scrollTop)
          live    <- isLive.get
          prevTop <- prevScrollPos.get
          start   <- startTime.get
          prevEnd <- prevEndRef.get
          end     <- endTime.get

          _       <-  if (liveTog && (live || prevTop != top)) {
                        for {
                          tlRect  <- IO(canvas.parentElement.getBoundingClientRect())
                          height  =  tlRect.height
                          _       <- IO(canvas.width = tlRect.width.toInt)
                          _       <- IO(canvas.height = tlRect.height.toInt)
                          width   <- IO(canvas.width - 200)
                          endTime <- IO(ZonedDateTime.now(java.time.ZoneId.of("UTC")))
                          _       <- IO(sizer.style.height = s"${convertTime(start, endTime)+2}px")
                          maxCol  <- parser.getMaxConcurrent()
                          events  <- parser.getEvents()
                          rects   = Rectangles.makeRectangles(endTime, height, top, width/maxCol, events)
                          _       <- rectRef.update(_ => rects)
                          _       <- drawCanvas(endTime, start, canvas, context, rects, maxCol, top, width)
                          _       <- prevScrollPos.update(_ => top)
                          _       <- if (top == 0.0) {
                                      isLive.update(_ => true)
                                    } else {
                                      isLive.update(_ => false)
                                    }
                        } yield ()
                      } else if (!liveTog && (prevEnd != end || prevTop != top)) {
                          for {
                            tlRect  <- IO(canvas.parentElement.getBoundingClientRect())
                            height  =  tlRect.height
                            _       <- IO(canvas.width = tlRect.width.toInt)
                            _       <- IO(canvas.height = tlRect.height.toInt)
                            width   <- IO(canvas.width - 200)
                            _       <- IO(sizer.style.height = s"${convertTime(start, end)+2}px")
                            maxCol  <- parser.getMaxConcurrent()
                            events  <- parser.getEvents()
                            rects   = Rectangles.makeRectangles(end, height, top, width/maxCol, events)
                            _       <- rectRef.update(_ => rects)
                            _       <- drawCanvas(end, start, canvas, context, rects, maxCol, top, width)
                            _       <- prevScrollPos.update(_ => top)
                            _       <- prevEndRef.set(end)
                            
                          } yield ()
                      } else {
                        IO.unit
                      }
          // _       <- if (live || prevTop != top) {
          //             for {
          //               tlRect  <- IO(canvas.parentElement.getBoundingClientRect())
          //               height  =  tlRect.height
          //               _       <- IO(canvas.width = tlRect.width.toInt)
          //               _       <- IO(canvas.height = tlRect.height.toInt)
          //               width   <- IO(canvas.width - 150)     // offset by 150 for total width of canvas that rectangles should cover
          //               // now     <- IO(ZonedDateTime.now(java.time.ZoneId.of("UTC")))
          //               // end     <- IO(now.toLocalDate.atStartOfDay(now.getZone()))
          //               _       <- IO(sizer.style.height = s"${convertTime(start, end)+2}px")
          //               maxCol  <- parser.getMaxConcurrent()
          //               // _       <-  makeRect(now, em.events, em.compEvents, em.rectangles, em.maxCounter, height, top, width)
          //               events  <- parser.getEvents()
          //               rects   = Rectangles.makeRectangles(end, height, top, width/maxCol, events)
          //               _       <- rectRef.update(_ => rects)
          //               _       <- drawCanvas(end, start, canvas, context, rects, maxCol, top, width)
          //               _       <- prevScrollPos.update(_ => top)
          //               _       <- if (top == 0.0) {
          //                           isLive.update(_ => true)
          //                         } else {
          //                           isLive.update(_ => false)
          //                         }
          //             } yield ()
          //           } else {
          //             IO.unit
          //           }
          _       <- IO.delay(dom.window.requestAnimationFrame(ts => 
                      dispatcher.unsafeRunAndForget(go(ts))))
        } yield()

      IO.delay(dom.window.requestAnimationFrame(ts => 
        dispatcher.unsafeRunAndForget(go(ts))))
      }


  /** 
  * Displays event information on hover over an event rectangle
  *
  * @param canvas
  * @param requestDetails HTML element to show event details of the event hovered over
  * @param rectRef
  */
  private def hover(
    canvas: HTMLCanvasElement,
    requestDetails: HTMLElement,
    rectRef: Ref[IO, List[Rectangle]]
    ): Resource[IO, Dispatcher[IO]]  =
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

