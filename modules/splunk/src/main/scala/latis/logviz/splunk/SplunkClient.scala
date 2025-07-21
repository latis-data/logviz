package latis.logviz.splunk

import cats.effect.IO
import cats.effect.Resource

trait SplunkClient {
  // Methods for querying Splunk
}

object SplunkClient {
  def make(/* config */): Resource[IO, SplunkClient] = {
    // Some work here to initialize the client. (Making HTTP client,
    // etc.)

    // new SplunkClient { ... }

    ???
  }
}
