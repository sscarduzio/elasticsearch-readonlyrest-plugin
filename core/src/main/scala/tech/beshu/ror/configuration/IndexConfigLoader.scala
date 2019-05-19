package tech.beshu.ror.configuration

import monix.eval.Task

class IndexConfigLoader extends ConfigLoader {
  override def load(): Task[RawRorConfig] = ???
}
