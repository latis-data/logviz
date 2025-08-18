package latis.logviz

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.LocalDateTime
import java.time.ZoneId

import org.scalajs.dom.HTMLInputElement
import cats.syntax.all.*
import cats.effect.IO
import cats.effect.Resource
import calico.html.io.{*, given}
import fs2.concurrent.SignallingRef
import fs2.dom.HtmlElement

// https://www.armanbilge.com/calico/demos/hello-world.html
class TimeRangeComponent(
  startRef: SignallingRef[IO, ZonedDateTime],
  endRef: SignallingRef[IO, ZonedDateTime],
  liveRef: SignallingRef[IO, Boolean]
) {
  val formatter= DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
// catch only catching datetime parse exception
  def render: Resource[IO, HtmlElement[IO]] =
    for {
      start <- Resource.eval(startRef.get)
      end   <- Resource.eval(endRef.get)

      
      startInput  <- div(
                      idAttr := "start-time",
                      p("Start: "),
                      input.withSelf{ self => 
                        (
                          // idAttr := "start-time",
                          `type` := "datetime-local",
                          value  := s"${start.format(formatter)}",
                          // maxAttr := s"${end.format(formatter)}",
                          maxAttr <-- endRef.map(time => time.format(formatter)),
                          minAttr := "1970-01-01 00:00",
                          //from https://www.armanbilge.com/calico/demos/counter.html
                          onChange --> {
                            _.evalMap(_ => self.value.get).filter(_.nonEmpty).foreach {v =>
                              val inputElem = self.asInstanceOf[HTMLInputElement]
                              if (inputElem.checkValidity()) {
                                // java.time.zone.ZoneRulesException: Unknown time-zone ID: America/Denver
                                // IO.println(test)
                                // >> IO.println(test2)

                                Either.catchOnly[DateTimeParseException](LocalDateTime.parse(v)) match
                                  case Left(value) => throw new IllegalArgumentException(
                                    "Invalid time") 
                                  case Right(value) => startRef.set(value.atZone(ZoneId.of("UTC")))
                              } else {
                                IO.unit
                              }
                              }
                          }
                          // onChange{ _ =>  
                          //   check non empty
                          //   val inputElm = self.asInstanceOf[HTMLInputElement]
                          //   if (inputElm.checkValidity()) {
                          //     self.value.get.flatMap(v => IO.println(v))
                          //   } else {
                          //     IO.unit
                          //   }
                          // }
                          // onChange --> (_.foreach(e =>   )
                        )
                       
                      },
                      //validity icon stuff from https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/input/datetime-local
                      span(cls := "validity"),
                    ) 
      endInput    <- div(
                      idAttr := "end-time",
                      p("End: "),
                      input.withSelf{ self => 
                        (
                          idAttr := "end-time",
                          `type` := "datetime-local",
                          // value := s"${end.format(formatter)}",
                          value <-- endRef.map(time => time.format(formatter)),
                          minAttr <-- startRef.map(time => time.format(formatter)),
                          onClick(_ => {
                            val inputElem = self.asInstanceOf[HTMLInputElement]
                            IO(inputElem.max = ZonedDateTime.now(ZoneId.of("UTC")).format(formatter))
                          }),
                          onChange --> {
                            _.evalMap(_ => self.value.get).filter(_.nonEmpty).foreach { v => 
                              val inputElem = self.asInstanceOf[HTMLInputElement]
                              if (inputElem.checkValidity()) {
                                Either.catchOnly[DateTimeParseException](LocalDateTime.parse(v)) match
                                  case Left(value) => throw new IllegalArgumentException(
                                    "Invalid time") 
                                  case Right(value) => {
                                    val time = value.atZone(ZoneId.of("UTC"))
                                    liveRef.set(false) >>
                                    endRef.set(time)
                                  }
                              } else {
                                IO.unit
                              }
                            }
                          }
                        )
                      },
                      span(cls := "validity")
                    )
      // liveButton  <- button(
      //                 `type` := "button",
      //                 "LIVE",
      //                 styleAttr <-- liveRef.map(bool => 
      //                                 bool match
      //                                   case true => "background-color: #db2a30"
      //                                   case false => "background-color: #FFFFFF"
      //                               ),
      //                 onClick(_ => {
      //                   for {
      //                     _ <- liveRef.update(bool => !bool)
      //                     _ <- endRef.set(ZonedDateTime.now(ZoneId.of("UTC")))
      //                     // test <- liveRef.get
      //                     // _    <- IO.println(test)
      //                   } yield ()
      //                 })
      //               )
      test  <- div(idAttr:= "time-range", div(idAttr:= "time-selection", startInput, endInput))

    } yield(test)
  
}

