package tech.beshu.ror.acl.blocks.variables.runtime

import cats.data.NonEmptyList
import cats.instances.either._
import cats.instances.list._
import cats.syntax.either._
import cats.syntax.traverse._
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.blocks.variables.Tokenizer.Token
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariable.ConvertError
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeSingleResolvableVariable.AlreadyResolved
import tech.beshu.ror.acl.blocks.variables.{Tokenizer, runtime}
import tech.beshu.ror.acl.domain.Header
import tech.beshu.ror.com.jayway.jsonpath.JsonPath

import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

object RuntimeResolvableVariableCreator extends Logging {

  final case class CreationError(msg: String) extends AnyVal

  def createSingleResolvableVariableFrom[T](text: String,
                                            convert: String => Either[ConvertError, T]): Either[CreationError, RuntimeSingleResolvableVariable[T]] = {
    Tokenizer
      .tokenize(text)
      .map(createVariable(_, VariableType.Single))
      .sequence
      .flatMap {
        case Nil =>
          Left(CreationError(s"Cannot create variable from '$text'"))
        case nonEmpty =>
          nonEmpty
            .foldLeft(Vector.empty[SingleExtractable].asRight[CreationError]) {
              case (Right(extractables), e: SingleExtractable) => Right(extractables :+ e)
              case (Right(_), _: MultiExtractable) => Left(CreationError("Cannot use multi-value variable"))
              case (l@Left(_), _) => l
            }
            .flatMap { singleExtractables =>
              val alreadyResolved = singleExtractables.collect { case c: SingleExtractable.Const => c }
              if (alreadyResolved.length == nonEmpty.length) {
                convert(alreadyResolved.map(_.value).foldLeft("")(_ + _))
                  .map(value => AlreadyResolved(value))
                  .left.map(e => CreationError(e.msg))
              } else {
                Right(RuntimeSingleResolvableVariable.ToBeResolved[T](singleExtractables, convert))
              }
            }
      }
  }

  def createMultiResolvableVariableFrom[T](text: String,
                                           convert: String => Either[ConvertError, T]): Either[CreationError, RuntimeMultiResolvableVariable[T]] = {
    Tokenizer
      .tokenize(text)
      .map(createVariable(_, VariableType.Multi))
      .sequence
      .flatMap {
        case Nil =>
          Left(CreationError(s"Cannot create variable from '$text'"))
        case nonEmpty =>
          val multiExtractables = nonEmpty.collect { case m: MultiExtractable => m }
          if(multiExtractables.length > 1) {
            Left(CreationError(s"Cannot use more than one multi-value variable"))
          } else {
            val multiExtractables = nonEmpty
              .collect {
                case s: SingleExtractable => MultiExtractable.fromSingleExtractable(s)
                case m: MultiExtractable => m
              }
              .toVector
            val alreadyResolved = multiExtractables.collect { case c: runtime.MultiExtractable.Const => c }
            if (alreadyResolved.length == nonEmpty.length) {
              convert(alreadyResolved.map(_.value).foldLeft("")(_ + _))
                .map(value => RuntimeMultiResolvableVariable.AlreadyResolved(NonEmptyList.one(value)))
                .left.map(e => CreationError(e.msg))
            } else {
              Right(RuntimeMultiResolvableVariable.ToBeResolved[T](multiExtractables, convert))
            }
          }
      }
  }

  private def createVariable(token: Token, `type`: VariableType): Either[CreationError, Extractable[_]] = token match {
    case Token.Text(value) =>
      createConst(value, `type`)
    case Token.Placeholder(name, _) =>
      createVarFrom(name, VariableType.Single)
    case Token.ExplodablePlaceholder(name, _) =>
      createVarFrom(name, VariableType.Multi)
  }

  private def createConst(value: String, `type`: VariableType) = Right {
    `type` match {
      case VariableType.Single => SingleExtractable.Const(value)
      case VariableType.Multi => MultiExtractable.Const(value)
    }
  }

  private def createVarFrom(value: String, `type`: VariableType) = {
    value match {
      case regexes.userVar() =>
        createUserId(`type`)
      case regexes.jwtPayloadPathVar(path) =>
        createJwtVariable(path, `type`)
      case regexes.explicitHeaderVar(headerName) =>
        createHeaderVariable(headerName, `type`)
      case other => // backward compatibility - assuming that it's header
        createHeaderVariable(other, `type`)
    }
  }

  private def createUserId(`type`: VariableType) = Right {
    `type` match {
      case VariableType.Single => SingleExtractable.UserIdVar
      case VariableType.Multi => MultiExtractable.UserIdVar
    }
  }

  private def createJwtVariable(jsonPathStr: String, `type`: VariableType) = {
    Try(JsonPath.compile(jsonPathStr)) match {
      case Success(compiledPath) => Right {
        `type` match {
          case VariableType.Single => SingleExtractable.JwtPayloadVar(compiledPath)
          case VariableType.Multi => MultiExtractable.JwtPayloadVar(compiledPath)
        }
      }
      case Failure(ex) =>
        logger.debug("Compiling JSON path failed", ex)
        Left(CreationError(s"Cannot create JWT variable, because cannot compile '$jsonPathStr' to JsonPath"))
    }
  }

  private def createHeaderVariable(headerNameStr: String, `type`: VariableType) = {
    NonEmptyString.unapply(headerNameStr) match {
      case Some(nes) => Right {
        `type` match {
          case VariableType.Single => SingleExtractable.HeaderVar(Header.Name(nes))
          case VariableType.Multi => MultiExtractable.HeaderVar(Header.Name(nes))
        }
      }
      case None =>
        Left(CreationError(s"Cannot create header variable, because no header name is passed"))
    }
  }

  private object regexes {
    val userVar: Regex = "user".r
    val explicitHeaderVar: Regex = "header:(.*)".r
    val jwtPayloadPathVar: Regex = "jwt:(.*)".r
  }

  private sealed trait VariableType
  private object VariableType {
    case object Single extends VariableType
    case object Multi extends VariableType
  }
}
