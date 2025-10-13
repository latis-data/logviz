package latis.logviz.model

enum RequestEvent{ 
  case Server(time: String)
  case Request(start: String, url: String)
  case Success(start: String, url: String, end: String, duration: Long)
  case Failure(start: String, url: String, end: String, msg: String)
  /**Events that are not request events and their previous matching events aren't in the mapping of ongoing events*/
  case Partial(end: String, msg: String)
}