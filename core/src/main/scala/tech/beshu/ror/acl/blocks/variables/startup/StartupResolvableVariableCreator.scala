package tech.beshu.ror.acl.blocks.variables.startup

import cats.data.NonEmptyList
import cats.instances.either._
import cats.instances.list._
import cats.syntax.traverse._
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.acl.blocks.variables.Tokenizer
import tech.beshu.ror.acl.blocks.variables.Tokenizer.Token
import tech.beshu.ror.acl.blocks.variables.startup.StartupMultiResolvableVariable.Wrapper
import tech.beshu.ror.acl.blocks.variables.startup.StartupResolvableVariableCreator.CreationError.{CannotUserMultiVariableInSingleVariableContext, InvalidVariableDefinition, OnlyOneMultiVariableCanBeUsedInVariableDefinition}
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName

import scala.util.matching.Regex

object StartupResolvableVariableCreator {

  sealed trait CreationError
  object CreationError {
    case object CannotUserMultiVariableInSingleVariableContext extends CreationError
    case object OnlyOneMultiVariableCanBeUsedInVariableDefinition extends CreationError
    final case class InvalidVariableDefinition(cause: String) extends CreationError
  }

  def createSingleVariableFrom(text: NonEmptyString): Either[CreationError, StartupSingleResolvableVariable] = {
    oldFashionedEnvAndTextHandling(text)
      .getOrElse(createSingleVariablesFrom(Tokenizer.tokenize(text)))
      .map { variables => VariableType.Single.createComposedVariable(variables) }
  }

  def createMultiVariableFrom(text: NonEmptyString): Either[CreationError, StartupMultiResolvableVariable] = {
    createMultiVariablesFrom(Tokenizer.tokenize(text))
      .map { variables => VariableType.Multi.createComposedVariable(variables) }
  }

  private def oldFashionedEnvAndTextHandling(text: NonEmptyString): Option[Either[CreationError, NonEmptyList[StartupSingleResolvableVariable]]] = {
    import cats.syntax.either._
    if (text.value.startsWith("env:")) {
      NonEmptyString
        .unapply(text.value.substring("env:".length))
        .map(str => VariableType.Single.createEnvVariable(EnvVarName(str)))
        .map(variable => NonEmptyList.one(variable).asRight[CreationError])
    } else if (text.value.startsWith("text:")) {
      Some {
        NonEmptyList.one(VariableType.Single.createTextVariable(text.value.substring("text:".length))).asRight[CreationError]
      }
    } else {
      None
    }
  }

  private def createSingleVariablesFrom(tokens: NonEmptyList[Token]): Either[CreationError, NonEmptyList[StartupSingleResolvableVariable]] = {
    tokens
      .map {
        case Token.Text(value) =>
          Right(VariableType.Single.createTextVariable(value))
        case Token.Placeholder(name, rawValue) =>
          name match {
            case regexes.envVar(envVarName) =>
              createEnvVariableFromString(envVarName, VariableType.Single)
            case _ =>
              rawValue match {
                case regexes.envVarOldStyle(envVarName) =>
                  createEnvVariableFromString(envVarName, VariableType.Single)
                case _ =>
                  Right(VariableType.Single.createTextVariable(rawValue))
              }
          }
        case Token.ExplodablePlaceholder(_, _) =>
          Left(CannotUserMultiVariableInSingleVariableContext)
      }
      .sequence
  }

  private def createMultiVariablesFrom(tokens: NonEmptyList[Token]): Either[CreationError, NonEmptyList[StartupMultiResolvableVariable]] = {
    val acc = tokens.foldLeft(Acc(Vector.empty, 0)) {
      case (Acc(results, multiVariableCount), token) => token match {
        case Token.Text(value) =>
          Acc(results :+ Right(VariableType.Multi.createTextVariable(value)), multiVariableCount)
        case Token.Placeholder(name, rawValue) =>
          Acc(results :+ variableFrom(name, rawValue, VariableType.Single).map(Wrapper.apply), multiVariableCount)
        case Token.ExplodablePlaceholder(name, rawValue) =>
          Acc(results :+ variableFrom(name, rawValue, VariableType.Multi), multiVariableCount + 1)
      }
    }
    if(acc.realMultiVariablesCount <= 1) {
      acc.results.toList.sequence.map(NonEmptyList.fromListUnsafe)
    } else {
      Left(OnlyOneMultiVariableCanBeUsedInVariableDefinition)
    }
  }

  private def variableFrom(name: String, rawValue: String, `type`: VariableType): Either[CreationError, `type`.T] = {
    name match {
      case regexes.envVar(envVarName) =>
        createEnvVariableFromString(envVarName, `type`)
      case _ =>
        Right(`type`.createTextVariable(rawValue))
    }
  }

  private def createEnvVariableFromString(envVarName: String, `type`: VariableType): Either[CreationError, `type`.T] = {
    NonEmptyString.unapply(envVarName) match {
      case Some(nes) => Right(`type`.createEnvVariable(EnvVarName(nes)))
      case None => Left(InvalidVariableDefinition("Empty ENV name passed"))
    }
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
    }
    case object Multi extends VariableType {
      override type T = StartupMultiResolvableVariable
      override def createEnvVariable(envName: EnvVarName): StartupMultiResolvableVariable =
        StartupMultiResolvableVariable.Env(envName)
      override def createTextVariable(value: String): StartupMultiResolvableVariable =
        StartupMultiResolvableVariable.Text(value)
      override def createComposedVariable(variables: NonEmptyList[StartupMultiResolvableVariable]): StartupMultiResolvableVariable =
        StartupMultiResolvableVariable.Composed(variables)
    }
  }

  private final case class Acc(results: Vector[Either[CreationError, StartupMultiResolvableVariable]],
                               realMultiVariablesCount: Int)

}
