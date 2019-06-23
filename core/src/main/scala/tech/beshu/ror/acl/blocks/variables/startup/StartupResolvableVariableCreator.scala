package tech.beshu.ror.acl.blocks.variables.startup

import cats.instances.either._
import cats.instances.list._
import cats.syntax.traverse._
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.acl.blocks.variables.Tokenizer
import tech.beshu.ror.acl.blocks.variables.Tokenizer.Token
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName

import scala.util.matching.Regex

object StartupResolvableVariableCreator {

  final case class CreationError(msg: String) extends AnyVal

  def createFrom(text: String): Either[CreationError, StartupResolvableVariable] = {
    oldFashionedEnvAndTextHandling(text).map(_ :: Nil)
      .getOrElse(Tokenizer.tokenize(text).map(createVariable))
      .sequence
      .map(Composed.apply)
  }

  private def oldFashionedEnvAndTextHandling(text: String): Option[Either[CreationError, StartupResolvableVariable]] = {
    if (text.startsWith("env:")) {
      NonEmptyString.unapply(text.substring("env:".length)).map(str => Env(EnvVarName(str))).map(Right.apply)
    } else if (text.startsWith("text:")) {
      Some(Right(Text(text.substring("text:".length))))
    } else {
      None
    }
  }

  private def createVariable(token: Token) = token match {
    case Token.Text(value) =>
      Right(Text(value))
    case Token.Placeholder(name, rawValue) =>
      name match {
        case regexes.envVar(envVarName) =>
          createEnvVariable(envVarName)
        case _ =>
          rawValue match {
            case regexes.envVarOldStyle(envVarName) =>
              createEnvVariable(envVarName)
            case _ =>
              Right(Text(rawValue))
          }
      }
  }

  private def createEnvVariable(envVarName: String) = {
    NonEmptyString.unapply(envVarName) match {
      case Some(nes) => Right(Env(EnvVarName(nes)))
      case None => Left(CreationError(s"Cannot create env variable, because no name of env variable is passed"))
    }
  }

  private object regexes {
    val envVar: Regex = "env:(.*)".r
    val envVarOldStyle: Regex = """\$\{(.*)\}""".r
  }

}
