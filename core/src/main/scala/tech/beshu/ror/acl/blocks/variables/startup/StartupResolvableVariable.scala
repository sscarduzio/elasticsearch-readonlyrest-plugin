package tech.beshu.ror.acl.blocks.variables.startup

import tech.beshu.ror.acl.blocks.variables.startup.StartupResolvableVariable.ResolvingError
import tech.beshu.ror.providers.EnvVarsProvider

private [startup] trait StartupResolvableVariable[RESULT] {

  def resolve(provider: EnvVarsProvider): Either[ResolvingError, RESULT]
}

object StartupResolvableVariable {
  final case class ResolvingError(msg: String) extends AnyVal
}
