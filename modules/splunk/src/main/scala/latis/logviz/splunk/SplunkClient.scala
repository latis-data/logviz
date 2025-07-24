package latis.logviz.splunk

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.Resource
import fs2.Stream
import io.circe.Json
import io.circe.generic.auto.*
import java.io.* // for file writing
import scala.io.StdIn.readLine 
import org.http4s.circe.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.*
import org.typelevel.ci.CIString
import scala.concurrent.duration.*

import latis.logviz.model.Event // importing Event.scala

// TODO: use these to parse Json through logviz event data model
// case class Message(`type`: String, text: String)
// case class Field(name: String)
// case class Results(_bkt: String, _cd: String, _indextime: String, _raw: String, _serial: String, _si: List[String], _sourcetype: String, _subsecond: String, _time: String, host: String, index: String, linecount: String, source: String, sourcetype: String, splunk_server: String)
// case class JsonResponse(preview: Boolean, init_offset: Int, messages: List[Message], fields: List[Field], results: List[Results], hightlighted: Option[Json])

trait SplunkClient {
  // Methods for querying Splunk
  def getSessionKey: IO[String]
  def generateQuery(sessionkey: String): IO[String]
  def checkQuery(sessionkey: String, sid: String): IO[Int]
  def waitLoop(sessionkey: String, sid: String): IO[Unit]
  def getResults(sessionkey: String, sid: String): IO[Json]
  def makeStream(response: Json): IO[Stream[IO, SplunkMessage]]
}

object SplunkClient { // the companion object stores static fields and methods
  def make(/* config */): Resource[IO, SplunkClient] = {
    // Some work here to initialize the client. (Making HTTP client,
    // etc.)
    EmberClientBuilder
      .default[IO]
      .build
      .map { client => 
        new SplunkClient { // new SplunkClient { ... }

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
                .withQueryParam("count", "5") // maximum number of results to return
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

          def makeStream(response: Json): IO[Stream[IO, SplunkMessage]] = {
            // TODO: need to parse the Json response into separate events
            val decodedResult = response.hcursor.downField("results").as[List[SplunkMessage]]
            decodedResult match {
              case Right(messages) => 
                // messages.foreach(println)
                IO.pure(Stream.emits(messages).covary[IO])
              case Left(error) =>
                IO.raiseError(new Exception(s"Error parsing Json into Stream with error: $error"))
            }

            // TODO: figure out how circe works
            // scala-cli --dep io.circe::circe-core:0.14.14 repl
            // Read, eval, print, loop
            // less fancy version of a notebook

            
            // TODO: Splunk response -> stream of Message -> stream of Event
            // for each page, produce a chunk of messages (what is the log message which you need to decode from Json)
            // stream of log messages, then look for the stuff below

            // in general, everything after this is the string message that we want

            /** HTTP/1.1 GET   - request
              * HTTP/1.1 200   - response
              * ERROR, Failed, failed - failure -> Request failed: ""
              * everything else - success -> Elapsed (ms): 27
              * for start -> Ember-Server service bound to address -> just get time (unix time)
              * any message that doesn't match these just ignore for now -- make a counter for these?
              */

              // write in terms of a pipeline: we have our splunk response
              // between stream of messages, stream of events
              // write a function Message => Option[Event]
              // messageStream.map(messageParseFunction).unNone (dont use these names though)
              // a cursor is immutable
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
        _          <- strm.compile.drain // TODO: to make the stream effectful
        _          <- IO.println("Finished...")
      } yield ()
    }
  }
}
