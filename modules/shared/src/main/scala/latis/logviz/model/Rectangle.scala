package latis.logviz.model

/**
  * Representation of request events as rectangles
  * 
  * To be used for drawing
  *
  * @param event tuple of request event and what column they belong to
  * @param x dependent on the number of columns and which column they will be in
  * @param y where event should start on canvas based on it's actual start time and viewport
  * @param width same as width of a column
  * @param height how long the event is (ongoing if event incomplete)
  * @param color indicator of whether request was successful or not
*/
final case class Rectangle(
  event: (RequestEvent, Int),
  x: Double,
  y: Double,
  width: Double,
  height: Double,
  color: String)