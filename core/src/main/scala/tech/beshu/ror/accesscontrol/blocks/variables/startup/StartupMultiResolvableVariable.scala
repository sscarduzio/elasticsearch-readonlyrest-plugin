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
import cats.instances.either.*
import cats.syntax.show.*
import cats.syntax.traverse.*
import com.github.tototoshi.csv.*
import tech.beshu.ror.accesscontrol.blocks.variables.startup.StartupResolvableVariable.ResolvingError
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.Function
import tech.beshu.ror.implicits.*
import tech.beshu.ror.tools.core.utils.EnvVarsProvider
import tech.beshu.ror.tools.core.utils.EnvVarsProvider.EnvVarName
import tech.beshu.ror.utils.ScalaOps.*

sealed trait StartupMultiResolvableVariable extends StartupResolvableVariable[NonEmptyList[String]]
object StartupMultiResolvableVariable {

  final class Env(name: EnvVarName, transformation: Option[Function]) extends StartupMultiResolvableVariable {
    private val csvParser =  new CSVParser(new DefaultCSVFormat {})
    override def resolve(provider: EnvVarsProvider): Either[ResolvingError, NonEmptyList[String]] = withTransformation {
      provider.getEnv(name) match {
        case Some(envValue) =>
          (for {
            values <- csvParser.parseLine(envValue)
            result <- NonEmptyList.fromList(values)
          } yield result) match {
            case Some(value) => Right(value)
            case None => Right(NonEmptyList.one(""))
          }
        case None =>
          Left(ResolvingError(s"Cannot resolve ENV variable '${name.show}'"))
      }
    }

    private def withTransformation(resolvable: => Either[ResolvingError, NonEmptyList[String]]) = transformation match {
      case Some(function) => resolvable.map(_.map(function.apply))
      case None => resolvable
    }
  }

  final case class Text(value: String) extends StartupMultiResolvableVariable {
    private val singleText = StartupSingleResolvableVariable.Text(value)
    override def resolve(provider: EnvVarsProvider): Either[ResolvingError, NonEmptyList[String]] =
      singleText.resolve(provider).map(NonEmptyList.one)
  }

  final case class Composed(vars: NonEmptyList[StartupResolvableVariable[NonEmptyList[String]]]) extends StartupMultiResolvableVariable {
    override def resolve(provider: EnvVarsProvider): Either[ResolvingError, NonEmptyList[String]] = {
      vars
        .map(_.resolve(provider))
        .sequence
        .map { resolvedVars =>
          resolvedVars.cartesian.map(_.toList.mkString)
        }
      }
  }

  final case class Wrapper(variable: StartupSingleResolvableVariable) extends StartupMultiResolvableVariable {
    override def resolve(provider: EnvVarsProvider): Either[ResolvingError, NonEmptyList[String]] =
      variable.resolve(provider).map(NonEmptyList.one)
  }

}
