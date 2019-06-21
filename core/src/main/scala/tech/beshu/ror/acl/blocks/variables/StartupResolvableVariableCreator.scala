package tech.beshu.ror.acl.blocks.variables

import cats.instances.either._
import cats.instances.list._
import cats.syntax.traverse._
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.acl.blocks.variables.Tokenizer.Token
import tech.beshu.ror.acl.domain.EnvVarName

import scala.util.matching.Regex

object StartupResolvableVariableCreator {

  final case class CreationError(msg: String)

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
          NonEmptyString.unapply(envVarName) match {
            case Some(nes) => Right(Env(EnvVarName(nes)))
            case None => Left(CreationError(s"No ENV variable name passed"))
          }
        case _ =>
          Right(Text(rawValue))
      }
  }

  private object regexes {
    val envVar: Regex = "env:(.*)".r
  }

}
