package tech.beshu.ror.acl.blocks.variables

import cats.syntax.show._
import cats.syntax.either._
import cats.instances.list._
import cats.instances.either._
import cats.syntax.traverse._
import tech.beshu.ror.acl.blocks.variables.StartupResolvableVariable.ResolvingError
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.EnvVarsProvider

sealed trait StartupResolvableVariable {
  def resolve(provider: EnvVarsProvider): Either[ResolvingError, String]
}

object StartupResolvableVariable {
  final case class ResolvingError(msg: String) extends AnyVal
}

final case class Env(name: EnvVarName) extends StartupResolvableVariable {
  override def resolve(provider: EnvVarsProvider): Either[ResolvingError, String] =
    Either.fromOption(provider.getEnv(name), ResolvingError(s"Cannot resolve ENV variable '${name.show}'"))
}

final case class Text(value: String) extends StartupResolvableVariable {
  override def resolve(provider: EnvVarsProvider): Either[ResolvingError, String] =
    Right(value)
}

final case class Composed(vars: List[StartupResolvableVariable]) extends StartupResolvableVariable {
  override def resolve(provider: EnvVarsProvider): Either[ResolvingError, String] = {
    vars.map(_.resolve(provider)).sequence.map(_.mkString)
  }
}