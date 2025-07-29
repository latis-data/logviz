package latis.logviz.splunk

import pureconfig.ConfigReader
import pureconfig.generic.derivation.default.*

case class SplunkConfig(
  uri: String,
  username: String,
  password: String
) derives ConfigReader