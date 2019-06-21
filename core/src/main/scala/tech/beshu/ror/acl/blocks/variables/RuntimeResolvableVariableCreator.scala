package tech.beshu.ror.acl.blocks.variables

import cats.instances.either._
import cats.instances.list._
import cats.syntax.traverse._
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.acl.blocks.variables.RuntimeResolvableVariable.ConvertError
import tech.beshu.ror.acl.blocks.variables.Tokenizer.Token
import tech.beshu.ror.acl.domain.Header
import tech.beshu.ror.com.jayway.jsonpath.JsonPath

import scala.language.postfixOps
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

object RuntimeResolvableVariableCreator {

  final case class CreationError(msg: String, ex: Option[Throwable] = None)

  def createFrom[T](text: String,
                    convert: String => Either[ConvertError, T]): Either[CreationError, RuntimeResolvableVariable[T]] = {
    Tokenizer
      .tokenize(text)
      .map(createVariable)
      .sequence
      .flatMap {
        case Nil => Left(CreationError(s"Cannot create variable from '$text'"))
        case nonEmpty =>
          val alreadyResolved = nonEmpty.collect { case c: Const => c }
          if (alreadyResolved.length == nonEmpty.length) {
            convert(alreadyResolved.map(_.value).foldLeft("")(_ + _))
              .map(AlreadyResolved.apply)
              .left.map(e => CreationError(e.msg))
          } else {
            Right(ToBeResolved[T](nonEmpty, convert))
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
          Try(JsonPath.compile(path)) match {
            case Success(compiledPath) => Right(JwtPayloadVar(compiledPath))
            case Failure(ex) => Left(CreationError(s"Cannot compile '$path' to JsonPath", Some(ex)))
          }
        case regexes.explicitHeaderVar(headerName) =>
          NonEmptyString.unapply(headerName) match {
            case Some(nes) => Right(HeaderVar(Header.Name(nes)))
            case None => Left(CreationError(s"No header name passed"))
          }
        case other => // backward compatibility - assuming that it's header
          NonEmptyString.unapply(other) match {
            case Some(nes) => Right(HeaderVar(Header.Name(nes)))
            case None => Left(CreationError(s"No header name passed"))
          }
      }
  }

  private object regexes {
    val userVar: Regex = "user".r
    val explicitHeaderVar: Regex = "header:(.*)".r
    val jwtPayloadPathVar: Regex = "jwt:(.*)".r
  }

}