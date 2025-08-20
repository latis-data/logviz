package latis.logviz.splunk

import cats.syntax.all.*
import org.http4s.Uri
import pureconfig.ConfigReader
import pureconfig.module.http4s.*

enum SplunkConfig {
  case Disabled
  case Enabled(
    uri: Uri,
    username: String,
    password: String
  )
}

object SplunkConfig {
  given ConfigReader[SplunkConfig] = ConfigReader.fromCursor { c =>
    c.asObjectCursor.flatMap { c => 
      c.atKey("enabled").flatMap(_.asBoolean).flatMap {
        case false => SplunkConfig.Disabled.pure[ConfigReader.Result]
        case true => (
          c.atKey("uri").flatMap(ConfigReader[Uri].from),
          c.atKey("username").flatMap(_.asString),
          c.atKey("password").flatMap(_.asString)
        ).mapN(SplunkConfig.Enabled.apply)
      }
    }
  }
}