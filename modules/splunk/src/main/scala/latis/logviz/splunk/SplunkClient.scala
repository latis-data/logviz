package latis.logviz.splunk

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.Resource
import fs2.Stream
import io.circe.Json
import java.io.* // for file writing
import scala.io.StdIn.readLine 
import org.http4s.circe.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.*
import org.typelevel.ci.CIString
import scala.concurrent.duration.*

import latis.logviz.model.Event

trait SplunkClient {
  // Methods for querying Splunk
  def getSessionKey: IO[String]
  def generateQuery(sessionkey: String): IO[String]
  def checkQuery(sessionkey: String, sid: String): IO[Int]
  def waitLoop(sessionkey: String, sid: String): IO[Unit]
  def getResults(sessionkey: String, sid: String): IO[Json]
  def makeStream(response: Json): IO[Stream[IO, Event]]
}

object SplunkClient {
  def make(/* config */): Resource[IO, SplunkClient] = {
    EmberClientBuilder
      .default[IO]
      .build
      .map { client => 
        new SplunkClient {
          val splunkuri: Uri = Uri.fromString(sys.env("SPLUNK_URI")).getOrElse(throw new RuntimeException("Invalid SPLUNK_URI"))

          // TODO: Should I use IO.blocking for readline()?
          def getSessionKey: IO[String] = {
            print("Username: ")
            val username = readLine()
            print("Password: ")
            val password = readLine()

            val request = Request[IO](
              method = Method.POST,
              uri = splunkuri / "services" / "auth" / "login"
            ).withEntity(
              UrlForm(
                "username" -> username,
                "password" -> password
              )
            )

            client.expect[String](request).flatMap{response =>
              // parsing response to get the session key
              val split = response.split("sessionKey")
              val sessionkey: String = split(1).split(">")(1).dropRight(2)
              IO.pure(sessionkey)
            }
          }

          def generateQuery(sessionkey: String): IO[String] = {
            // print("Input a query: ")
            // val query = "search " + readLine()
            val query = "search index=latis source=latis3-swp* earliest_time=-24h@h latest_time=now"

            val request = Request[IO](
              method = Method.POST,
              uri = splunkuri / "services" / "search" / "jobs"
            ).putHeaders(
              Header.Raw(CIString("Authorization"), s"Splunk $sessionkey")
            ).withEntity(
              UrlForm(
                "search" -> query
              )
            )

            client.expect[String](request).flatMap{response =>
              val sid = response.split("sid")(1).drop(1).dropRight(2)
              IO.pure(sid)
            }
          }

          def checkQuery(sessionkey: String, sid: String): IO[Int] = {
            // TODO: implement failing cases and tests
            val request = Request[IO](
              method = Method.GET,
              uri = splunkuri / "services" / "search" / "jobs" / sid
            ).putHeaders(
              Header.Raw(CIString("Authorization"), s"Splunk $sessionkey")
            )

            client.expect[String](request).flatMap{response =>
              // <s:key name="dispatchState">RUNNING</s:key>
              // <s:key name="isDone">0</s:key>
              // <s:key name="isFailed">0</s:key>
              
              // parsing to get dispatchState
              val state = response.split("dispatchState")(1).split("doneProgress")(0).split("<")(0).drop(2)
              println(s"State: $state")

              // parsing to get isDone boolean
              val done: Int = response.split("isDone")(1).split("isEventsPreviewEnabled")(0).split("<")(0).drop(2).toInt
              println(s"Done: $done")

              // parsing to get isFailed boolean
              val failed: Int = response.split("isFailed")(1).split("isFinalized")(0).split("<")(0).drop(2).toInt
              println(s"Failed: $failed")
              print("\n")

              IO.pure(done)
            }
          }

          def waitLoop(sessionkey: String, sid: String): IO[Unit] = {
            for {
              status <- checkQuery(sessionkey, sid)
              _      <- if (status == 1) IO.unit
                        else IO.sleep(2.seconds) *> waitLoop(sessionkey, sid)
            } yield ()
          }

          def getResults(sessionkey: String, sid: String): IO[Json] = {
            val request = Request[IO](
              method = Method.GET,
              uri = (splunkuri / "services" / "search" / "jobs" / sid / "events") // use /results if we want transformed events (performing stats or operations on events)
                .withQueryParam("output_mode", "json")
                .withQueryParam("offset", "0") // index of the first result to return
                .withQueryParam("count", "100") // maximum number of results to return
            ).putHeaders(
              Header.Raw(CIString("Authorization"), s"Splunk $sessionkey")
            ) 

            // TODO: Make a loop to continually get results until the amount of results returned is less than offset

            client.expect[Json](request).flatMap{response =>
              val fileWriter = new FileWriter(new File("output.txt"))
              fileWriter.write(response.toString)
              fileWriter.close()
              IO.pure(response)
            }
          }

          def makeStream(response: Json): IO[Stream[IO, Event]] = {
            // making a stream of messages
            val decodedResult = response.hcursor.downField("results").as[List[SplunkMessage]]
            val mStrm: Stream[IO, SplunkMessage] = decodedResult match {
              case Right(messages) => 
                Stream.emits(messages).covary[IO]
              case Left(error) =>
                Stream.raiseError(new Exception(s"Error parsing Json into Stream with error: $error"))
            }

            val getEvent: SplunkMessage => Option[Event] = (m: SplunkMessage) => {
              val line: String = m.hcursor.downField("line").as[String].getOrElse("unknown")

              // using contains method -- is there a better way to do this?
              if line.contains("HTTP/1.1 GET") then
                val spl = line.split("HTTP/1.1 GET")
                val id = spl(0).split("request-id=")(1).dropRight(3)
                val time = spl(0).split(" INFO")(0).drop(1)
                val request = line.split("HTTP/1.1 GET")(1).drop(1)
                Some(Event.Request(id, time, request))
              else if line.contains("HTTP/1.1 ") then
                val spl = line.split("HTTP/1.1 ")
                val id = spl(0).split("request-id=")(1).dropRight(3)
                val time = spl(0).split(" INFO")(0).drop(1)
                val status = spl(1).split(" OK")(0).toInt
                Some(Event.Response(id, time, status))
              else if line.contains("Elapsed ") then
                val spl = line.split("Elapsed ")
                val id = spl(0).split("request-id=")(1).dropRight(3)
                val time = spl(0).split(" INFO")(0).drop(1)
                val duration = spl(1).split("source")(0).drop(6).toInt
                Some(Event.Success(id, time, duration))
              else if line.contains("Ember-Server service bound to address:") then
                val time = line.split(" INFO")(0).drop(1)
                Some(Event.Start(time))
              else if line.contains("request failed: ") then
                // TODO: an example of full output to finish implementing this
                val id = "unknown"
                val time = "unknown"
                val msg = line.split("request failed: ")(1)
                Some(Event.Failure(id, time, msg))
              else
                None
            }

            val eStrm: Stream[IO, Event] = mStrm.map(getEvent).unNone
            IO.pure(eStrm)
          }
        }
      }
  }
}

object app extends IOApp.Simple {
  override def run: IO[Unit] = {
    SplunkClient.make().use { sclient => // creating a resource and then using it
      for {
        sessionkey <- sclient.getSessionKey // Step 1: Get a session key
        sid        <- sclient.generateQuery(sessionkey) // Step 2: Generate a query
        done       <- sclient.waitLoop(sessionkey, sid) // Step 3: Check the status of a query
        res        <- sclient.getResults(sessionkey, sid) // Step 4: Get the results from the query
        strm       <- sclient.makeStream(res) // making a stream of log events
        _          <- strm.compile.drain // TODO: to make the stream effectful - execute the stream and discard the results
        _          <- IO.println("Finished...")
      } yield ()
    }
  }
}
