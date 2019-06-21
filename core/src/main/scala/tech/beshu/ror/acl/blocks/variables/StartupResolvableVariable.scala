package tech.beshu.ror.acl.blocks.variables

import cats.syntax.show._
import cats.syntax.either._
import cats.instances.list._
import cats.instances.either._
import cats.syntax.traverse._
import tech.beshu.ror.acl.blocks.variables.StartupResolvableVariable.ExtractError
import tech.beshu.ror.acl.domain.EnvVarName
import tech.beshu.ror.utils.EnvVarsProvider
import tech.beshu.ror.acl.show.logs._

sealed trait StartupResolvableVariable {
  def extract(provider: EnvVarsProvider): Either[StartupResolvableVariable.ExtractError, String]
}

object StartupResolvableVariable {
  final case class ExtractError(msg: String) extends AnyVal
}

final case class Env(name: EnvVarName) extends StartupResolvableVariable {
  override def extract(provider: EnvVarsProvider): Either[ExtractError, String] =
    Either.fromOption(provider.getEnv(name), ExtractError(s"Cannot resolve ENV variable '${name.show}'"))
}

final case class Text(value: String) extends StartupResolvableVariable {
  override def extract(provider: EnvVarsProvider): Either[ExtractError, String] =
    Right(value)
}

final case class Composed(vars: List[StartupResolvableVariable]) extends StartupResolvableVariable {
  override def extract(provider: EnvVarsProvider): Either[ExtractError, String] = {
    vars.map(_.extract(provider)).sequence.map(_.mkString)
  }
}