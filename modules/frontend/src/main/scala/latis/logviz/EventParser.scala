package latis.logviz

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.PQueue
import cats.syntax.all.*

import latis.logviz.model.RequestEvent
import latis.logviz.model.Event

trait EventParser {
  def parse(event: Event): IO[Unit]
  def getEvents(): IO[List[(RequestEvent, Int)]]
  def getMaxConcurrent(): IO[Int]
 
}

object EventParser {
  def apply(): IO[EventParser] = {
    for {
      //ongoing request events mapped to concurrent depth
      eventsRef 		<- Ref[IO].of(Map[String, (RequestEvent, Int)]())
      //completed events(with a finished time) mapped to concurrent depth
      compEventsRef <- Ref[IO].of(List[(RequestEvent, Int)]())
      //counter for number of concurrent events 
      colCounter 		<- Ref[IO].of(0)
      //max number of concurrent events at once
      maxCounter 		<- Ref[IO].of(0)
      //priority queue to keep track of which concurrency depth to tie a request event to
      pq 						<- PQueue.bounded[IO, Int](100)
      _ 						<- (0 to 99).toList.traverse(pq.offer(_))
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
              c				<- colCounter.updateAndGet(c => c + 1)
              _ 			<- maxCounter.update(prev => math.max(prev, c))
              _ 			<- compEventsRef.update(lst => 
                          (RequestEvent.Server(time), cDepth) +: lst
                        )
              _				<- colCounter.update(c => c - 1)
              _				<- pq.offer(cDepth)
            } yield()

          case Event.Request(id, time, request) => 
            for {
              cDepth  <- pq.take
              c       <- colCounter.updateAndGet(c => c + 1)
              _       <- maxCounter.update(prev => math.max(prev, c))
              _       <- eventsRef.update(map =>
                          map + (id -> (RequestEvent.Request(time, request), cDepth))
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
                            case None => throw new IllegalArgumentException(
                              "No request of this id found??")
                          }
                _       <- eventsRef.update(m => m - id)
                _       <- colCounter.update(c => c - 1)
                _       <- pq.offer(cDepth)
              } yield ()
            } else {
              IO.unit
            }

          case Event.Success(id, time, duration) => 
            for {
              map     <- eventsRef.get
              dResult <- map.get(id) match {
                          case Some((RequestEvent.Request(start, url), currDepth)) =>
                            compEventsRef.update(lst =>
                              (RequestEvent.Success(start, url, time, duration), 
                              currDepth) +: lst)
                            >> IO(Some(currDepth))
                          case Some(_) => throw new IllegalArgumentException(
                            "Got an event that is not request, something is wrong") 
                          case None => 
                            // request was not within the time range but the success was
                            IO.pure(None)
                        }
              _       <- dResult match {
                          case Some(cDepth) =>
                            for {
                              _  <- eventsRef.update(m => m - id)
                              _  <- colCounter.update(c => c - 1)
                              _  <- pq.offer(cDepth)
                            } yield ()
                          case None =>
                            IO.unit
              }
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
                          case Some(_) => throw new IllegalArgumentException(
                            "Got an event that is not request, something is wrong") 
                          case None => throw new IllegalArgumentException(
                            "No request of this id found??")
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
                            case (_, (event, cDepth)) => (event, cDepth) :: acc
                        })
          compEvents  <- compEventsRef.get
        } yield(compEvents ++ events)

      override def getMaxConcurrent(): IO[Int] = {
        maxCounter.get.flatMap { count =>
          if (count == 0) {
            IO.pure(1)
          } else {
            IO.pure(count)
          }
        }
      }
    }
  }
}
