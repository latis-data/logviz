package latis.logviz

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.Resource
import com.comcast.ip4s.ipv4
import org.http4s.ember.server.EmberServerBuilder
import pureconfig.* 
import pureconfig.module.catseffect.syntax.*

import latis.logviz.splunk.*

/**
 * Sets up server and maps logviz routes from [[latis.logviz.LogvizRoutes]]
 * 
 * This uses the defined routes in LogvizRoutes and if a request includes an invalid route, we respond with a not found. 
 * 
 * ShutdownTimeout of 5 seconds forcefully shuts down server in 5 seconds after stopping app
*/
object Main extends IOApp.Simple {
  val eventSource: Resource[IO, EventSource] = for {
    splunkConf <- Resource.eval(ConfigSource.default.at("logviz.splunk").loadF[IO, SplunkConfig]())
    source     <- splunkConf match {
      case SplunkConfig.Disabled => Resource.pure[IO, JSONEventSource](JSONEventSource())
      case SplunkConfig.Enabled(uri, username, password) => SplunkClient.make(uri, username, password).map(client => SplunkEventSource(client))
    }
  } yield source

  override def run: IO[Unit] =
    eventSource.use { es =>
      EmberServerBuilder
        .default[IO]
        .withHost(ipv4"0.0.0.0")
        .withHttpApp(LogvizRoutes().routes(es).orNotFound)
        .withShutdownTimeout(5.seconds)
        .build
        .useForever
    }
}
