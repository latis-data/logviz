package latis.logviz

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.PQueue
import cats.syntax.all.*

import latis.logviz.model.RequestEvent
import latis.logviz.model.Event


private def getUnusedColumn(pq: PQueue[IO, Column], acc: List[Column] = Nil): IO[Option[Column]] = {
  //instead of keeping track of the number of concurrent columns and subtracting from the max for base case, I can just use tryTake!
  pq.tryTake.flatMap{
    case Some(col) =>  
      if (col.used) {
        getUnusedColumn(pq, col :: acc)
      } else {
          acc.traverse(pq.offer(_))
          >> IO(Some(col.copy(used = true)))
      }
    //pq is empty: meaning no columns that are unused -> need to increase max number of columns
    case None =>
      acc.traverse(pq.offer(_))
      >> IO(None)
    }
}

trait EventParser {
  def parse(event: Event): IO[Unit]
  def getEvents(): IO[List[(RequestEvent, Int)]]
  def getMaxConcurrent(): IO[Int]
 
}

object EventParser {
  def apply(): IO[EventParser] = {
    for {
      //ongoing request events mapped to concurrent depth
      eventsRef 		<- Ref[IO].of(Map[String, (RequestEvent, Column)]())
      //completed events(with a finished time) mapped to concurrent depth
      compEventsRef <- Ref[IO].of(List[(RequestEvent, Column)]())
      //counter for number of concurrent events 
      colCounter 		<- Ref[IO].of(0)
      //max number of concurrent events at once
      maxCounter 		<- Ref[IO].of(1)
      //priority queue to keep track of which concurrency depth to tie a request event to
      pq 						<- PQueue.bounded[IO, Column](100)
      _ 						<- (0 to 99).toList.traverse(i => pq.offer(Column(i, false)))
      // _ 						<- (0 to 99).toList.traverse(pq.offer(_))
    } yield new EventParser {

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
      override def parse(event: Event): IO[Unit] =
        event match {
          case Event.Start(time) =>
            for {
              cDepth	<- pq.take
              //can set to true later after if care about keeping track of the first event of that column
              usedCol =  cDepth.copy(used = true)
              c				<- colCounter.updateAndGet(c => c + 1)
              _ 			<- maxCounter.update(prev => math.max(prev, c))
              _ 			<- compEventsRef.update(lst => 
                          (RequestEvent.Server(time), usedCol) +: lst
                        )
              _				<- colCounter.update(c => c - 1)
              _				<- pq.offer(usedCol)
            } yield()

          case Event.Request(id, time, request) => 
            for {
              cDepth  <- pq.take
              usedCol =  cDepth.copy(used = true)
              c       <- colCounter.updateAndGet(c => c + 1)
              _       <- maxCounter.update(prev => math.max(prev, c))
              _       <- eventsRef.update(map =>
                          map + (id -> (RequestEvent.Request(time, request), usedCol))
                        )
            } yield ()

          case Event.Response(id, time, status) => 
            if (status >= 400) {
              for {
                map     <- eventsRef.get
                cDepth  <- map.get(id) match {
                            case Some((RequestEvent.Request(start, url), currDepth)) =>
                              compEventsRef.update(lst => 
                                (RequestEvent.Failure(start, url, time, status.toString),
                                currDepth) +: lst)  
                              >> IO(currDepth)
                            case Some(_) => throw new IllegalArgumentException(
                              "Got an event that is not request, something is wrong") 
                            // case None => throw new IllegalArgumentException(
                            //   "No request of this id found??")
                            case None =>
                              //color to be adjusted
                              //got an event whose request is outside the time range. Since status is 400, its complete

                              for {
                                currDepth <- getUnusedColumn(pq).flatMap{
                                              case Some(value) => IO(value)
                                              case None => throw new IllegalArgumentException(
                                                "BADD!! No unused column available. Increase number of columns!!!") 
                                            }
                                c         <- colCounter.updateAndGet(c => c + 1)
                                _         <- maxCounter.update(prev => math.max(prev, c))
                                _         <- compEventsRef.update(lst => 
                                              (RequestEvent.Partial(time, status.toString()),
                                              currDepth) +: lst)
                              } yield(currDepth)


                          }
                _       <- eventsRef.update(m => m - id)
                _       <- colCounter.update(c => c - 1)
                _       <- pq.offer(cDepth)
              } yield ()
            } else {
              //could be an ongoing partial event if theres no matching id
              //normally not storing responses, but we would want to in the partial event case since this is the "first instance" that we see for this event
              // IO.unit

              for {
                map <- eventsRef.get
                _   <- map.get(id) match {
                        case Some(_) => IO.unit
                        case None =>
                          for {
                            cDepth  <- getUnusedColumn(pq).flatMap{
                                        case Some(value) => IO(value)
                                        case None => throw new IllegalArgumentException(
                                          "No unused column available. Increase number of columns!!!")
                                      }
                            c       <- colCounter.updateAndGet(c => c + 1)
                            _       <- maxCounter.update(prev => math.max(prev, c))
                            _       <- eventsRef.update(map =>
                                        map + (id -> (RequestEvent.Partial(time, status.toString()), cDepth))
                                      )
                          } yield ()
                      }
              } yield()

            }

          case Event.Success(id, time, duration) => 
            for {
              map     <- eventsRef.get
              dResult <- map.get(id) match {
                          case Some((RequestEvent.Request(start, url), currDepth)) =>
                            compEventsRef.update(lst =>
                              (RequestEvent.Success(start, url, time, duration), 
                              currDepth) +: lst)
                            >> IO(currDepth)
                          case Some((RequestEvent.Partial(start, status), currDepth)) =>
                            compEventsRef.update(lst =>
                              (RequestEvent.Partial(time, s"successful event with response status of: $status, duration: $duration"), 
                              currDepth) +: lst)
                            >> IO(currDepth)
                          case Some(_) => throw new IllegalArgumentException(
                            "Got an event that is not request or partial, something is wrong") 
                          // case None => throw new IllegalArgumentException(
                          //   "No request of this id found??") 
                          case None => 
                            // request and success response somewhere in the past outside of date range
                            for {
                              currDepth <- getUnusedColumn(pq).flatMap{
                                            case Some(value) => IO(value)
                                            case None => throw new IllegalArgumentException(
                                              "No unused column available. Increase number of columns!!!")
                                          }
                              c         <- colCounter.updateAndGet(c => c + 1)
                              _         <- maxCounter.update(prev => math.max(prev, c))
                              _         <- compEventsRef.update(lst => 
                                            (RequestEvent.Partial(time, s"partial success event- start time unknown. Duration: $duration"), 
                                            currDepth) +: lst)
                            } yield(currDepth)
                        }
              _       <- eventsRef.update(m => m - id)
              _       <- colCounter.update(c => c - 1)
              _       <- pq.offer(dResult)
            } yield ()

          case Event.Failure(id, time, msg) => 
            for {
              map     <- eventsRef.get
              cDepth  <- map.get(id) match {
                          case Some((RequestEvent.Request(start, url), currDepth)) =>
                            compEventsRef.update(lst =>
                              (RequestEvent.Failure(start, url, time, msg), 
                              currDepth) +: lst)
                            >> IO(currDepth)
                          case Some((RequestEvent.Partial(start, status), currDepth)) =>
                            compEventsRef.update(lst =>
                              (RequestEvent.Partial(time, s"successful event with response status of: $status, error msg: $msg"), 
                              currDepth) +: lst)
                            >> IO(currDepth)
                          case Some(_) => throw new IllegalArgumentException(
                            "Got an event that is not request or partial, something is wrong") 
                          case None =>
                            for {
                              currDepth <- getUnusedColumn(pq).flatMap{
                                            case Some(value) => IO(value)
                                            case None => throw new IllegalArgumentException(
                                              "No unused column available. Increase number of columns!!!")
                                          }
                              c         <- colCounter.updateAndGet(c => c + 1)
                              _         <- maxCounter.update(prev => math.max(prev, c))
                              _         <- compEventsRef.update(lst => 
                                            (RequestEvent.Partial(time, s"partial failure event- start time unknown. Error: $msg"), 
                                            currDepth) +: lst)
                            } yield(currDepth)
                        }
              _       <- eventsRef.update(m => m - id)
              _       <- colCounter.update(c => c - 1)
              _       <- pq.offer(cDepth)
            } yield ()
        }
    
      override def getEvents(): IO[List[(RequestEvent, Int)]] =
        for {
          incomp      <- eventsRef.get
          events      <- IO(incomp.foldLeft(List[(RequestEvent, Int)]()){ (acc, map) =>
                          map match
                            case (_, (event, cDepth)) => (event, cDepth.number) :: acc
                        })
          compEvents  <- compEventsRef.get
          comp        =  compEvents.map((event, col) => (event, col.number))
        } yield(comp ++ events)

      override def getMaxConcurrent(): IO[Int] = {
        maxCounter.get
      }
    }
  }
}
