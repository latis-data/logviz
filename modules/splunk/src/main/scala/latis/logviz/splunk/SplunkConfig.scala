package latis.logviz.splunk

import cats.syntax.all.*
import org.http4s.Uri
import pureconfig.ConfigReader
import pureconfig.module.http4s.*

enum SplunkConfig {
  case Disabled
  case Enabled(auth: AuthType)
}

enum AuthType {
  case UserPass(
    uri: Uri,
    username: String,
    password: String,
    source: String,
    index: String
  )
  case Token(
    uri: Uri,
    token: String,
    source: String,
    index: String
  )
}

object SplunkConfig {
  given ConfigReader[SplunkConfig] = ConfigReader.fromCursor { c =>
    c.asObjectCursor.flatMap { c => 
      c.atKey("enabled").flatMap(_.asBoolean).flatMap {
        case false => SplunkConfig.Disabled.pure[ConfigReader.Result]
        case true =>
          c.atKey("auth").flatMap(_.asString) match {
            case Right(auth) => auth match {
              case "user-pass-auth" =>
                println("User-pass")
                (
                  c.atKey("uri").flatMap(ConfigReader[Uri].from),
                  c.atKey("username").flatMap(_.asString),
                  c.atKey("password").flatMap(_.asString),
                  c.atKey("source").flatMap(_.asString),
                  c.atKey("index").flatMap(_.asString)
                ).mapN(AuthType.UserPass.apply).map(SplunkConfig.Enabled.apply)
              case "token-auth" =>
                println("token")
                (
                  c.atKey("uri").flatMap(ConfigReader[Uri].from),
                  c.atKey("token").flatMap(_.asString),
                  c.atKey("source").flatMap(_.asString),
                  c.atKey("index").flatMap(_.asString)
                ).mapN(AuthType.Token.apply).map(SplunkConfig.Enabled.apply)
            }
            case Left(err) =>
              throw new Exception(f"Unable to parse AuthType: $err")
          }
      }
    }
  }
}