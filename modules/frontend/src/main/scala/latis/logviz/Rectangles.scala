package latis.logviz

import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime

import cats.effect.IO
import cats.syntax.all.*

import latis.logviz.model.RequestEvent
import latis.logviz.model.Rectangle


object Rectangles{
  val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss VV")
  val pixelsPerSec = 1.0
  val startOffset = 150
  // val rectangleRef = Ref[IO].of(List[Rectangle]())

  def makeRectangles(
    currTime: ZonedDateTime,
    height: Double,
    top: Double, //offset from scroll, top of canvas
    width: Int, //available width to determine width of each rectangle could probably replace width and maxcounter with cwidth
    events: List[(RequestEvent, Int)]
  ): IO[List[Rectangle]] = IO{
    val bottomY = top + height
    val rects: List[Rectangle] = events.foldLeft(List[Rectangle]()){ (acc, event) =>
        event match {
          case (RequestEvent.Server(time), cDepth) => 
            val eventTime = ZonedDateTime.parse(time, formatter)
            //position in pixels of event relative to the current time(or chosen current time)
            val y = Duration.between(eventTime, currTime).toSeconds() * pixelsPerSec
            //new position of event given the scroll offset
            // val y = startY - top
            
            //as long as the start of our event is below the start canvas viewport and the end of our event is above the end of viewport, then we're guaranteed to draw this event.
            //should cover the edge cases of when only part of the event may show on the viewport 
            if (y >= top && y - 2 < bottomY) {
              //x position is the offset + the column starting position. So if event starts at the first column, cDepth = 0, then it should just be the offset value.
              // y position is the pixel position of y given by the scroll offset. So if the start of the canvas should be 10, and the event starts at 10, we obviously dont want the event to actually start at 10 but at 0 instead
              Rectangle((RequestEvent.Server(time), cDepth),
              startOffset + cDepth * width, y - top, width, -2, "green") :: acc
            } else {
              acc
            }

          case (RequestEvent.Request(start, url), cDepth) =>
            val eventTime = ZonedDateTime.parse(start, formatter)
            val y = Duration.between(eventTime, currTime).toSeconds() * pixelsPerSec
            //since this is an ongoing event, then it's always going to be showing on the canvas 
            //if the start of event goes past the bottom of view port, then the whole viewport for that column should be filled by this event
            if (y >= top) {
              Rectangle((RequestEvent.Request(start, url), cDepth),
              startOffset + cDepth * width, y-top, width, -(y-top), "green") :: acc
            } else {
              acc
            }

          case (RequestEvent.Success(start, url, end, duration), cDepth) =>
            val eventTime = ZonedDateTime.parse(start, formatter)
            val endTime = ZonedDateTime.parse(end, formatter)
            val y = Duration.between(eventTime, currTime).toSeconds() * pixelsPerSec
            val y_end = Duration.between(endTime, currTime).toSeconds() * pixelsPerSec

            if (y >= top && y_end < bottomY) {
              Rectangle((RequestEvent.Success(start, url, end, duration), cDepth),
              startOffset + cDepth * width, y-top, width, y_end-y, "green") :: acc
            } else {
              acc
            }
          
          case (RequestEvent.Failure(start, url, end, msg), cDepth) =>
            val eventTime = ZonedDateTime.parse(start, formatter)
            val endTime = ZonedDateTime.parse(end, formatter)
            val y = Duration.between(eventTime, currTime).toSeconds() * pixelsPerSec
            val y_end = Duration.between(endTime, currTime).toSeconds() * pixelsPerSec

            if (y >= top && y_end < bottomY) {
              Rectangle((RequestEvent.Failure(start, url, end, msg), cDepth),
              startOffset + cDepth * width, y-top, width, y_end-y, "red") :: acc
            } else {
              acc
            }
        }
    }
    rects
  }
}