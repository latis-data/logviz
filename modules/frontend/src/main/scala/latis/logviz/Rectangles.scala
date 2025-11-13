package latis.logviz

import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime

import latis.logviz.model.RequestEvent
import latis.logviz.model.Rectangle


object Rectangles{
  val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
  //startOffset is used for when to start drawing the columns. Since we have timestamps drawn on the same canvas, we can't just start at x=0. 
  val startOffset = 150


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
    * @param startTime to be used for partial events
    * @return list of rectangles
    */
  def makeRectangles(
    currTime: LocalDateTime,
    height: Double,
    top: Double,
    width: Int, 
    events: List[(RequestEvent, Int)],
    startTime: LocalDateTime,
    pixelsPerSec: Double
  ): List[Rectangle] =
    //top(how much we've scrolled from the top) + height(viewport) tells us what the bottom "timestamp"/y position currently is
    val bottomY = top + height
    val rects: List[Rectangle] = events.foldLeft(List[Rectangle]()){ (acc, event) =>
        event match {
          case (RequestEvent.Server(time), cDepth) => 
            val eventTime = LocalDateTime.parse(time, formatter)
            //gets the y starting position of the event given its starting time 
            //and the current time(time at top of canvas, which could be either live time or the end time set by date range)
            val y = Duration.between(eventTime, currTime).toSeconds() * pixelsPerSec

            //start time of event is before the current time with scroll offset and thus its y value is greater the current scrolled top of viewport value
            // and since server event has only a start time, we apply an additional width of 2 pixels to allow drawing and hovering over the event easier. 
            // so if y-2(starting point) is less than the bottom of viewport, meaning that the time for this event is above the starting time/bottom of canvas 
            if (y >= top && y - 2 < bottomY) {
              Rectangle((RequestEvent.Server(time), cDepth),
              //offset + the concurrency level + the width of each column tells us the x position of the event which is equal to the x position that the column that the event is in starts at. 
              //y-top gives us the y value to draw the event at since the canvas is offset by the scroll top pixels. 
              startOffset + cDepth * width, y - top, width, -2, "green") :: acc
            } else {
              acc
            }

          case (RequestEvent.Request(start, url), cDepth) =>
            val eventTime = LocalDateTime.parse(start, formatter)
            val y = Duration.between(eventTime, currTime).toSeconds() * pixelsPerSec
            
            //any remaining request events here are ongoing events so as long as they are below the top of the viewport, then we make the rectangle
            if (y >= top) {
              Rectangle((RequestEvent.Request(start, url), cDepth),
              startOffset + cDepth * width, y-top, width, -(y-top), "green") :: acc
            } else {
              acc
            }

          case (RequestEvent.Success(start, url, end, duration), cDepth) =>
            val eventTime = LocalDateTime.parse(start, formatter)
            val endTime = LocalDateTime.parse(end, formatter)
            val y = Duration.between(eventTime, currTime).toSeconds() * pixelsPerSec
            val y_end = Duration.between(endTime, currTime).toSeconds() * pixelsPerSec

            //not only needs to be below top of viewport but also 
            //the end time needs to be above the bottom of the viewport
            if (y >= top && y_end < bottomY) {
              Rectangle((RequestEvent.Success(start, url, end, duration), cDepth),
              startOffset + cDepth * width, y-top, width, y_end-y, "green") :: acc
            } else {
              acc
            }
          
          case (RequestEvent.Failure(start, url, end, msg), cDepth) =>
            val eventTime = LocalDateTime.parse(start, formatter)
            val endTime = LocalDateTime.parse(end, formatter)
            val y = Duration.between(eventTime, currTime).toSeconds() * pixelsPerSec
            val y_end = Duration.between(endTime, currTime).toSeconds() * pixelsPerSec

            if (y >= top && y_end < bottomY) {
              Rectangle((RequestEvent.Failure(start, url, end, msg), cDepth),
              startOffset + cDepth * width, y-top, width, y_end-y, "red") :: acc
            } else {
              acc
            }
          
          case (RequestEvent.Partial(time, msg), cDepth) =>
            val endTime = LocalDateTime.parse(time, formatter)
            val y = Duration.between(startTime, currTime).toSeconds() * pixelsPerSec
            val y_end = Duration.between(endTime, currTime).toSeconds() * pixelsPerSec

            if (y >= top && y_end < bottomY) {
              //scrolled to the point where we should see the partial event go into the past
              if (y-top <= height) {
                Rectangle((RequestEvent.Partial(time, msg), cDepth),
                startOffset + cDepth * width, height, width, y_end-y, "green") :: acc
              } else {
                Rectangle((RequestEvent.Partial(time, msg), cDepth),
                startOffset + cDepth * width, y-top, width, y_end-y, "green") :: acc
              }
            } else {
              acc
            }
        }
    }
    rects
}