package tech.beshu.ror.acl.blocks.variables.runtime

import cats.data.NonEmptyList
import cats.instances.either._
import cats.instances.list._
import cats.syntax.traverse._
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.blocks.variables.Tokenizer
import tech.beshu.ror.acl.blocks.variables.Tokenizer.Token
import tech.beshu.ror.acl.blocks.variables.runtime.MultiExtractable.{Const, HeaderVar, JwtPayloadVar, UserIdVar}
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeMultiResolvableVariable.{ToBeResolved, AlreadyResolved}
import tech.beshu.ror.acl.blocks.variables.runtime.Variable.ConvertError
import tech.beshu.ror.acl.domain.Header
import tech.beshu.ror.com.jayway.jsonpath.JsonPath

import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

// todo: refactoring needed
object RuntimeMultiResolvableVariableCreator extends Logging {

  final case class CreationError(msg: String) extends AnyVal

  def createFrom[T](text: String,
                    convert: String => Either[ConvertError, T]): Either[CreationError, RuntimeMultiResolvableVariable[T]] = {
    Tokenizer
      .tokenize(text)
      .map(createVariable)
      .sequence
      .flatMap {
        case Nil =>
          Left(CreationError(s"Cannot create variable from '$text'"))
        case nonEmpty =>
          val alreadyResolved = nonEmpty.collect { case c: Const => c }
          if (alreadyResolved.length == nonEmpty.length) {
            convert(alreadyResolved.map(_.value).foldLeft("")(_ + _))
              .map(value => AlreadyResolved(NonEmptyList.one(value)))
              .left.map(e => CreationError(e.msg))
          } else {
            Right(ToBeResolved(nonEmpty, convert))
          }
      }
  }

  private def createVariable(token: Token) = token match {
    case Token.Text(value) =>
      Right(Const(value))
    case Token.Placeholder(name, _) =>
      name match {
        case regexes.userVar() =>
          Right(UserIdVar)
        case regexes.jwtPayloadPathVar(path) =>
          createJwtVariable(path)
        case regexes.explicitHeaderVar(headerName) =>
          createHeaderVariable(headerName)
        case other => // backward compatibility - assuming that it's header
          createHeaderVariable(other)
      }
  }

  private def createJwtVariable(jsonPathStr: String) = {
    Try(JsonPath.compile(jsonPathStr)) match {
      case Success(compiledPath) => Right(JwtPayloadVar(compiledPath))
      case Failure(ex) =>
        logger.debug("Compiling JSON path failed", ex)
        Left(CreationError(s"Cannot create JWT variable, because cannot compile '$jsonPathStr' to JsonPath"))
    }
  }

  private def createHeaderVariable(headerNameStr: String) = {
    NonEmptyString.unapply(headerNameStr) match {
      case Some(nes) => Right(HeaderVar(Header.Name(nes)))
      case None => Left(CreationError(s"Cannot create header variable, because no header name is passed"))
    }
  }

  private object regexes {
    val userVar: Regex = "user".r
    val explicitHeaderVar: Regex = "header:(.*)".r
    val jwtPayloadPathVar: Regex = "jwt:(.*)".r
  }

}
