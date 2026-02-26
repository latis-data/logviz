package latis.logviz

import cats.effect.IO

/** Describes an instance source that can enumerate LaTiS instances */
trait InstanceSource {
  /** Enumerates LaTiS instances */
  def instances: IO[List[String]]
}