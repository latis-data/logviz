package latis.logviz.splunk

import cats.syntax.all.*
import org.http4s.Uri
import pureconfig.ConfigReader
import pureconfig.error.*
import pureconfig.module.http4s.*

enum SplunkConfig {
  case Disabled
  case Enabled(
    uri: Uri,
    source: String,
    index: String,
    auth: AuthType
  )
}

enum AuthType {
  case UserPass(
    username: String,
    password: String
  )
  case Token(token: String)
}

object SplunkConfig {
  given ConfigReader[SplunkConfig] = ConfigReader.fromCursor { c =>
    c.asObjectCursor.flatMap { c => 
      c.atKey("enabled").flatMap(_.asBoolean).flatMap {
        case false => SplunkConfig.Disabled.pure[ConfigReader.Result]
        case true =>
          (
            c.atKey("uri").flatMap(ConfigReader[Uri].from),
            c.atKey("source").flatMap(_.asString),
            c.atKey("index").flatMap(_.asString),
            c.atKey("auth").flatMap(_.asString).flatMap { authString =>
              authString.toLowerCase match {
                case "user-pass-auth" =>
                  (
                    c.atKey("username").flatMap(_.asString),
                    c.atKey("password").flatMap(_.asString)
                  ).mapN(AuthType.UserPass.apply)
                case "token-auth" =>
                  (c.atKey("token").flatMap(_.asString)).map(AuthType.Token.apply)
                case s: String => c.failed(CannotConvert(s, "AuthType", s"$s is not a valid auth type"))
              }
            }
          ).mapN(SplunkConfig.Enabled.apply)
      }
    }
  }
}