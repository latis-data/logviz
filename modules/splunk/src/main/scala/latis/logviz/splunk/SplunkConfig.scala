package latis.logviz.splunk

import pureconfig.ConfigReader

case class SplunkConfig(
  uri: String,
  username: String,
  password: String
) derives ConfigReader