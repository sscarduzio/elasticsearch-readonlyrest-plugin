package tech.beshu.ror.configuration

import monix.eval.Task
import tech.beshu.ror.utils.EnvVarsProvider

class FileConfigLoader(envVarsProvider: EnvVarsProvider) extends ConfigLoader {
  override def load(): Task[RawRorConfig] = ???
}
