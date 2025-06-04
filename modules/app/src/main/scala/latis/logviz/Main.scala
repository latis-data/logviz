package latis.logviz

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.IOApp
import com.comcast.ip4s.ipv4
import org.http4s.ember.server.EmberServerBuilder

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
