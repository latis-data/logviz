package latis.logviz

import cats.effect.IO

/** Describes an instance source that events come from */
trait InstanceSource {
  /** Gets events from a given instance */
  def instances: IO[List[(String, Long)]]
}