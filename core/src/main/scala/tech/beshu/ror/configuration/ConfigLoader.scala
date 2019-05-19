package tech.beshu.ror.configuration

import monix.eval.Task

trait ConfigLoader {

  def load(): Task[RawRorConfig]
}
