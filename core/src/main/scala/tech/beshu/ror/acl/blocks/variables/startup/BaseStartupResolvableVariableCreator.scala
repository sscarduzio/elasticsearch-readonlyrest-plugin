package tech.beshu.ror.acl.blocks.variables.startup

import cats.data.NonEmptyList
import cats.instances.either._
import cats.instances.list._
import cats.syntax.traverse._
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.acl.blocks.variables.Tokenizer
import tech.beshu.ror.acl.blocks.variables.Tokenizer.Token
import tech.beshu.ror.acl.blocks.variables.startup.BaseStartupResolvableVariableCreator.CreationError
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName

import scala.util.matching.Regex

trait BaseStartupResolvableVariableCreator[T] {

  protected def createEnvVariable(envName: EnvVarName): StartupResolvableVariable[T]
  protected def createTextVariable(value: String): StartupResolvableVariable[T]
  protected def createComposedVariable(variables: NonEmptyList[StartupResolvableVariable[T]]): StartupResolvableVariable[T]

  def createFrom(text: String): Either[CreationError, StartupResolvableVariable[T]] = {
    oldFashionedEnvAndTextHandling(text).map(_ :: Nil)
      .getOrElse(Tokenizer.tokenize(text).map(createVariable))
      .sequence
      .flatMap { variables =>
        NonEmptyList.fromList(variables) match {
          case Some(nel) => Right(createComposedVariable(nel))
          case None => Left(CreationError("Cannot create non empty list of variables"))
        }
      }
  }

  private def oldFashionedEnvAndTextHandling(text: String): Option[Either[CreationError, StartupResolvableVariable[T]]] = {
    if (text.startsWith("env:")) {
      NonEmptyString.unapply(text.substring("env:".length)).map(str => createEnvVariable(EnvVarName(str))).map(Right.apply)
    } else if (text.startsWith("text:")) {
      Some(Right(createTextVariable(text.substring("text:".length))))
    } else {
      None
    }
  }

  private def createVariable(token: Token) = token match {
    case Token.Text(value) =>
      Right(createTextVariable(value))
    case Token.Placeholder(name, rawValue) =>
      name match {
        case regexes.envVar(envVarName) =>
          createEnvVariableFromString(envVarName)
        case _ =>
          rawValue match {
            case regexes.envVarOldStyle(envVarName) =>
              createEnvVariableFromString(envVarName)
            case _ =>
              Right(createTextVariable(rawValue))
          }
      }
    case Token.ExplodablePlaceholder(name, rawValue) =>
      ??? // todo: impl
  }

  private def createEnvVariableFromString(envVarName: String) = {
    NonEmptyString.unapply(envVarName) match {
      case Some(nes) => Right(createEnvVariable(EnvVarName(nes)))
      case None => Left(CreationError(s"Cannot create env variable, because no name of env variable is passed"))
    }
  }

  private object regexes {
    val envVar: Regex = "env:(.*)".r
    val envVarOldStyle: Regex = """\$\{(.*)\}""".r
  }

}

object BaseStartupResolvableVariableCreator {

  final case class CreationError(msg: String) extends AnyVal
}
