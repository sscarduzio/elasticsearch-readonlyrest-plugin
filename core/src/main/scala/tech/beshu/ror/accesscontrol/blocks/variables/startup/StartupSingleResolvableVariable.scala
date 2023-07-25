/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.accesscontrol.blocks.variables.startup

import cats.data.NonEmptyList
import cats.instances.either._
import cats.syntax.either._
import cats.syntax.show._
import cats.syntax.traverse._
import tech.beshu.ror.accesscontrol.blocks.variables.startup.StartupResolvableVariable.ResolvingError
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.Function
import tech.beshu.ror.accesscontrol.show.logs._
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

  final class TransformationApplyingResolvableDecorator(underlying: StartupSingleResolvableVariable,
                                                        transformation: Function) extends StartupSingleResolvableVariable {
    override def resolve(provider: EnvVarsProvider): Either[ResolvingError, String] = {
      underlying.resolve(provider)
        .map(transformation.apply)
    }
  }
}
