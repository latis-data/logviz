package latis.logviz

import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime

import latis.logviz.model.RequestEvent
import latis.logviz.model.Rectangle


object Rectangles{
  val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss VV")
  val pixelsPerSec = 1.0
  val startOffset = 200


  /**
    * Creates list of rectangles from given time range and list of events
    * 
    * For each event, we determine what its position would be relative to the current time at the top
    * Then we determine whether any part of this event would be in the viewport of the canvas
    * This is done by comparing its start position to the top and end position to the bottom.
    * If the start position is greater than or equal to the top, then we know the event starts somewhere at or after the 
    * start of the viewport. So that would either mean the start of the event is within the viewport or below it. 
    * Then, since the end of the event must come after the start, the end will always be less than the start. 
    * So, if the end time is less than the bottom of the viewport, we know the end time position must be either in the viewport or above
    * Meaning that if both conditions are true, we have at least some part of the event that will be seen in the viewport so we make the rectangle to be drawn.
    *
    * @param currTime
    * @param height
    * @param top offset in pixels from the top of the "canvas" top of canvas is at that offset
    * @param width width of each rectangle
    * @param events 
    * @return list of rectangles
    */
  def makeRectangles(
    currTime: ZonedDateTime,
    height: Double,
    top: Double,
    width: Int, 
    events: List[(RequestEvent, Int)]
  ): List[Rectangle] =
    val bottomY = top + height
    val rects: List[Rectangle] = events.foldLeft(List[Rectangle]()){ (acc, event) =>
        event match {
          case (RequestEvent.Server(time), cDepth) => 
            val eventTime = ZonedDateTime.parse(time, formatter)
            val y = Duration.between(eventTime, currTime).toSeconds() * pixelsPerSec

            if (y >= top && y - 2 < bottomY) {
              Rectangle((RequestEvent.Server(time), cDepth),
              startOffset + cDepth * width, y - top, width, -2, "green") :: acc
            } else {
              acc
            }

          case (RequestEvent.Request(start, url), cDepth) =>
            val eventTime = ZonedDateTime.parse(start, formatter)
            val y = Duration.between(eventTime, currTime).toSeconds() * pixelsPerSec
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