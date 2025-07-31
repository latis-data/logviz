package latis.logviz.splunk

import org.http4s.Uri
import pureconfig.ConfigReader
import pureconfig.module.http4s.*

case class SplunkConfig(
  uri: Uri,
  username: String,
  password: String
) derives ConfigReader