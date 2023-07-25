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
import cats.instances.list._
import cats.syntax.traverse._
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.blocks.variables.{Tokenizer, VariableCreationConfig}
import tech.beshu.ror.accesscontrol.blocks.variables.Tokenizer.Token
import tech.beshu.ror.accesscontrol.blocks.variables.Tokenizer.Token.Transformation
import tech.beshu.ror.accesscontrol.blocks.variables.startup.StartupMultiResolvableVariable.Wrapper
import tech.beshu.ror.accesscontrol.blocks.variables.startup.StartupResolvableVariableCreator.CreationError._
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler.CompilationError
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.Function
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName

import scala.util.matching.Regex

object StartupResolvableVariableCreator {

  sealed trait CreationError
  object CreationError {
    case object CannotUserMultiVariableInSingleVariableContext extends CreationError
    case object OnlyOneMultiVariableCanBeUsedInVariableDefinition extends CreationError
    final case class InvalidVariableDefinition(cause: String) extends CreationError
  }

  def createSingleVariableFrom(text: NonEmptyString)
                              (implicit config: VariableCreationConfig): Either[CreationError, StartupSingleResolvableVariable] = {
    oldFashionedEnvAndTextHandling(text, VariableType.Single)
      .getOrElse(createSingleVariablesFrom(Tokenizer.tokenize(text)))
      .map { variables => VariableType.Single.createComposedVariable(variables) }
  }

  def createMultiVariableFrom(text: NonEmptyString)
                             (implicit config: VariableCreationConfig): Either[CreationError, StartupMultiResolvableVariable] = {
    createMultiVariablesFrom(Tokenizer.tokenize(text))
      .map { variables => VariableType.Multi.createComposedVariable(variables) }
  }

  private def oldFashionedEnvAndTextHandling(text: NonEmptyString, `type`: VariableType): Option[Either[CreationError, NonEmptyList[`type`.T]]] = {
    import cats.syntax.either._
    if (text.value.startsWith("env:")) {
      NonEmptyString
        .unapply(text.value.substring("env:".length))
        .map(str => `type`.createEnvVariable(EnvVarName(str)))
        .map(variable => NonEmptyList.one(variable).asRight[CreationError])
    } else if (text.value.startsWith("text:")) {
      Some {
        NonEmptyList.one(`type`.createTextVariable(text.value.substring("text:".length))).asRight[CreationError]
      }
    } else {
      None
    }
  }

  private def createSingleVariablesFrom(tokens: NonEmptyList[Token])
                                       (implicit config: VariableCreationConfig): Either[CreationError, NonEmptyList[StartupSingleResolvableVariable]] = {
    tokens
      .map {
        case Token.Text(value) =>
          Right(VariableType.Single.createTextVariable(value))
        case Token.Placeholder(name, rawValue, maybeTransformation) =>
          name match {
            case regexes.envVar(envVarName) =>
              createEnvVariable(envVarName, VariableType.Single, maybeTransformation)
            case _ =>
              rawValue match {
                case regexes.envVarOldStyle(envVarName) =>
                  createEnvVariable(envVarName, VariableType.Single, maybeTransformation)
                case _ =>
                  createTextVariable(rawValue, VariableType.Single, maybeTransformation)
              }
          }
        case Token.ExplodablePlaceholder(_, _, _) =>
          Left(CannotUserMultiVariableInSingleVariableContext)
      }
      .sequence
  }

  private def createMultiVariablesFrom(tokens: NonEmptyList[Token])
                                      (implicit config: VariableCreationConfig): Either[CreationError, NonEmptyList[StartupMultiResolvableVariable]] = {
    val acc = tokens.foldLeft(Acc(Vector.empty, 0)) {
      case (Acc(results, multiVariableCount), token) => token match {
        case Token.Text(value) =>
          Acc(results :+ Right(VariableType.Multi.createTextVariable(value)), multiVariableCount)
        case Token.Placeholder(name, rawValue, maybeTransformation) =>
          val variable = variableFrom(name, rawValue, maybeTransformation, VariableType.Single).map(Wrapper.apply)
          Acc(results :+ variable, multiVariableCount)
        case Token.ExplodablePlaceholder(name, rawValue, maybeTransformation) =>
          val variable = variableFrom(name, rawValue, maybeTransformation, VariableType.Multi)
          Acc(results :+ variable, multiVariableCount + 1)
      }
    }
    if (acc.realMultiVariablesCount <= 1) {
      acc.results.toList.sequence.map(NonEmptyList.fromListUnsafe)
    } else {
      Left(OnlyOneMultiVariableCanBeUsedInVariableDefinition)
    }
  }

  private def variableFrom(name: String,
                           rawValue: String,
                           maybeTransformation: Option[Transformation],
                           `type`: VariableType)
                          (implicit config: VariableCreationConfig): Either[CreationError, `type`.T] = {
    name match {
      case regexes.envVar(envVarName) =>
        createEnvVariable(envVarName, `type`, maybeTransformation)
      case _ if rawValue.startsWith("$") => // backward compatibility (old ${EX} === new @{env:EX})
        createEnvVariable(name, `type`, maybeTransformation)
      case _ =>
        createTextVariable(rawValue, `type`, maybeTransformation)
    }
  }

  private def createTextVariable(rawValue: String,
                                 `type`: VariableType,
                                 maybeTransformation: Option[Transformation])
                                (implicit config: VariableCreationConfig) = {
    for {
      variable <- Right(`type`.createTextVariable(rawValue))
      finalVariable <-
        maybeTransformation
          .map { transformation =>
            compile(transformation).map { _ =>
              `type`.createComposedVariable(
                NonEmptyList.of(
                  variable,
                  `type`.createTextVariable(transformation.rawValue) // we should not apply transformation on text variable
                )
              )
            }
          }
          .getOrElse(Right(variable))
    } yield finalVariable
  }

  private def createEnvVariable(envVarName: String,
                                `type`: VariableType,
                                maybeTransformation: Option[Transformation])
                               (implicit config: VariableCreationConfig): Either[CreationError, `type`.T] = {
    for {
      variable <- createEnvVariableFromString(envVarName, `type`)
      finalVariable <-
        maybeTransformation
          .map(compile(_).map(`type`.createTransformedVariable(variable, _)))
          .getOrElse(Right(variable))
    } yield finalVariable
  }

  private def createEnvVariableFromString(envVarName: String, `type`: VariableType): Either[CreationError, `type`.T] = {
    NonEmptyString.unapply(envVarName) match {
      case Some(nes) => Right(`type`.createEnvVariable(EnvVarName(nes)))
      case None => Left(InvalidVariableDefinition("Empty ENV name passed"))
    }
  }

  private def compile(transformation: Token.Transformation)
                     (implicit config: VariableCreationConfig) = {
    config.transformationCompiler
      .compile(transformation.name)
      .left.map(toCreationError(transformation.name, _))
  }

  private def toCreationError(transformationStr: String,
                              error: TransformationCompiler.CompilationError): CreationError = error match {
    case CompilationError.UnableToParseTransformation(message) =>
      CreationError.InvalidVariableDefinition(s"Unable to parse transformation string: [$transformationStr]. Cause: $message")
    case CompilationError.UnableToCompileTransformation(message) =>
      CreationError.InvalidVariableDefinition(s"Unable to compile transformation string: [$transformationStr]. Cause: $message")
  }

  private object regexes {
    val envVar: Regex = "env:(.*)".r
    val envVarOldStyle: Regex = """\$\{(.*)\}""".r
  }

  private sealed trait VariableType {
    type T <: StartupResolvableVariable[_]
    def createEnvVariable(envName: EnvVarName): T
    def createTextVariable(value: String): T
    def createComposedVariable(variables: NonEmptyList[T]): T
    def createTransformedVariable(variable: T, transformation: Function): T
  }
  private object VariableType {
    case object Single extends VariableType {
      override type T = StartupSingleResolvableVariable
      override def createEnvVariable(envName: EnvVarName): StartupSingleResolvableVariable =
        StartupSingleResolvableVariable.Env(envName)
      override def createTextVariable(value: String): StartupSingleResolvableVariable =
        StartupSingleResolvableVariable.Text(value)
      override def createComposedVariable(variables: NonEmptyList[StartupSingleResolvableVariable]): StartupSingleResolvableVariable =
        StartupSingleResolvableVariable.Composed(variables)
      override def createTransformedVariable(variable: StartupSingleResolvableVariable,
                                             transformation: Function): StartupSingleResolvableVariable = {
        new StartupSingleResolvableVariable.TransformationApplyingResolvableDecorator(variable, transformation)
      }
    }
    case object Multi extends VariableType {
      override type T = StartupMultiResolvableVariable
      override def createEnvVariable(envName: EnvVarName): StartupMultiResolvableVariable =
        StartupMultiResolvableVariable.Env(envName)
      override def createTextVariable(value: String): StartupMultiResolvableVariable =
        StartupMultiResolvableVariable.Text(value)
      override def createComposedVariable(variables: NonEmptyList[StartupMultiResolvableVariable]): StartupMultiResolvableVariable =
        StartupMultiResolvableVariable.Composed(variables)
      override def createTransformedVariable(variable: StartupMultiResolvableVariable,
                                             transformation: Function): StartupMultiResolvableVariable = {
        new StartupMultiResolvableVariable.TransformationApplyingResolvableDecorator(variable, transformation)
      }
    }
  }

  private final case class Acc(results: Vector[Either[CreationError, StartupMultiResolvableVariable]],
                               realMultiVariablesCount: Int)

}
