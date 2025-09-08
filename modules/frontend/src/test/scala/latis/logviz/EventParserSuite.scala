package latis.logviz

import latis.logviz.model.Event
import latis.logviz.model.RequestEvent


class EventParserSuite extends munit.CatsEffectSuite {
//https://github.com/typelevel/munit-cats-effect
  test("parse one event") {
     for {
      eParser   <- EventParser()
      testEvent = Event.Start("2025-09-09 14:00:00")
      _         <- eParser.parse(testEvent)
      _         <- assertIO(eParser.getEvents(), List((RequestEvent.Server("2025-09-09 14:00:00"), 0)))
      _         <- assertIO(eParser.getMaxConcurrent(), 1)
    } yield()
  }
  test("parse multiple events") {
    // val events = List(
    //   Event.Request()
    // )
  }
}