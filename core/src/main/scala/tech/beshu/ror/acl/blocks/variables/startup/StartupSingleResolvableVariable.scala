package tech.beshu.ror.acl.blocks.variables.startup

import cats.data.NonEmptyList
import cats.instances.either._
import cats.syntax.either._
import cats.syntax.show._
import cats.syntax.traverse._
import tech.beshu.ror.acl.blocks.variables.startup.StartupResolvableVariable.ResolvingError
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.EnvVarsProvider

sealed trait StartupSingleResolvableVariable extends StartupResolvableVariable[String]
object StartupSingleResolvableVariable {

  final case class Env(name: EnvVarName) extends StartupSingleResolvableVariable {
    override def resolve(provider: EnvVarsProvider): Either[ResolvingError, String] =
      Either.fromOption(provider.getEnv(name), ResolvingError(s"Cannot resolve ENV variable '${name.show}'"))
  }

  final case class Text(value: String) extends StartupSingleResolvableVariable {
    override def resolve(provider: EnvVarsProvider): Either[ResolvingError, String] =
      Right(value)
  }

  final case class Composed(vars: NonEmptyList[StartupResolvableVariable[String]]) extends StartupSingleResolvableVariable {
    override def resolve(provider: EnvVarsProvider): Either[ResolvingError, String] = {
      vars.map(_.resolve(provider)).sequence.map(_.toList.mkString)
    }
  }
}
