package latis.logviz

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.IOApp
import com.comcast.ip4s.ipv4
import org.http4s.ember.server.EmberServerBuilder

/**
 * Sets up server and maps logviz routes from [[latis.logviz.LogvizRoutes]]
 * 
 * This uses the defined routes in LogvizRoutes and if a request includes an invalid route, we respond with a not found. 
 * 
 * ShutdownTimeout of 5 seconds forcefully shuts down server in 5 seconds after stopping app
*/
object Main extends IOApp.Simple {
  override def run: IO[Unit] =
    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withHttpApp(LogvizRoutes.routes.orNotFound)
      .withShutdownTimeout(5.seconds)
      .build
      .useForever
}
