package tech.beshu.ror.acl.blocks.variables.runtime

import cats.data.NonEmptyList
import cats.instances.either._
import cats.instances.list._
import cats.syntax.traverse._
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.blocks.variables.Tokenizer.Token
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariable.ConvertError
import tech.beshu.ror.acl.blocks.variables.{Tokenizer, runtime}
import tech.beshu.ror.acl.domain.Header
import tech.beshu.ror.com.jayway.jsonpath.JsonPath

import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

object RuntimeResolvableVariableCreator extends Logging {

  final case class CreationError(msg: String) extends AnyVal

  def createSingleResolvableVariableFrom[T](text: NonEmptyString,
                                            convert: String => Either[ConvertError, T]): Either[CreationError, RuntimeSingleResolvableVariable[T]] = {
    singleExtactablesFrom(Tokenizer.tokenize(text))
      .flatMap(variableFromSingleExtractables(_, convert))
  }

  def createMultiResolvableVariableFrom[T](text: NonEmptyString,
                                           convert: String => Either[ConvertError, T]): Either[CreationError, RuntimeMultiResolvableVariable[T]] = {
    multiExtractablesFrom(Tokenizer.tokenize(text))
      .flatMap(variableFromMultiExtractables(_, convert))
  }

  private def singleExtactablesFrom(tokens: NonEmptyList[Token]): Either[CreationError, NonEmptyList[SingleExtractable]] = {
    tokens
      .map {
        case Token.Text(value) =>
          Right(ExtractableType.Single.createConstExtractable(value))
        case Token.Placeholder(name, _) =>
          createExtractableFromToken(name, ExtractableType.Single)
        case Token.ExplodablePlaceholder(_, _) =>
          Left(CreationError("Cannot use multi value variable in non-array context"))
      }
      .sequence
  }

  private def variableFromSingleExtractables[T](extractables: NonEmptyList[SingleExtractable],
                                                convert: String => Either[ConvertError, T]) = {
    val alreadyResolved = extractables.collect { case c: SingleExtractable.Const => c }
    if (alreadyResolved.length == extractables.length) {
      convert(alreadyResolved.map(_.value).foldLeft("")(_ + _))
        .map(value => RuntimeSingleResolvableVariable.AlreadyResolved(value))
        .left.map(e => CreationError(e.msg))
    } else {
      Right(RuntimeSingleResolvableVariable.ToBeResolved[T](extractables, convert))
    }
  }

  private def multiExtractablesFrom(tokens: NonEmptyList[Token]): Either[CreationError, NonEmptyList[MultiExtractable]] = {
    val acc = tokens.foldLeft(Acc(Vector.empty, 0)) {
      case (Acc(results, multiExtractableCount), token) => token match {
        case Token.Text(value) =>
          Acc(results :+ Right(ExtractableType.Multi.createConstExtractable(value)), multiExtractableCount)
        case Token.Placeholder(name, _) =>
          Acc(results :+ createExtractableFromToken(name, ExtractableType.Single).map(MultiExtractable.fromSingleExtractable), multiExtractableCount)
        case Token.ExplodablePlaceholder(name, _) =>
          Acc(results :+ createExtractableFromToken(name, ExtractableType.Multi), multiExtractableCount + 1)
      }
    }
    if(acc.multiExtractableCount <= 1) {
      acc.results.toList.sequence.map(NonEmptyList.fromListUnsafe)
    } else {
      Left(CreationError(s"Cannot use more than one multi-value variable"))
    }
  }

  private def variableFromMultiExtractables[T](extractables: NonEmptyList[MultiExtractable],
                                               convert: String => Either[ConvertError, T]) = {
    val alreadyResolved = extractables.collect { case c: runtime.MultiExtractable.Const => c }
    if (alreadyResolved.length == extractables.length) {
      convert(alreadyResolved.map(_.value).foldLeft("")(_ + _))
        .map(value => RuntimeMultiResolvableVariable.AlreadyResolved(NonEmptyList.one(value)))
        .left.map(e => CreationError(e.msg))
    } else {
      Right(RuntimeMultiResolvableVariable.ToBeResolved[T](extractables, convert))
    }
  }

  private def createExtractableFromToken[VALUE](value: String, `type`: ExtractableType): Either[CreationError, `type`.TYPE] = {
    value match {
      case regexes.userVar() =>
        Right(`type`.createUserIdExtractable())
      case regexes.jwtPayloadPathVar(path) =>
        createJwtExtractable(path, `type`)
      case regexes.explicitHeaderVar(headerName) =>
        createHeaderExtractable(headerName, `type`)
      case other => // backward compatibility - assuming that it's header
        createHeaderExtractable(other, `type`)
    }
  }

  private def createJwtExtractable(jsonPathStr: String, `type`: ExtractableType): Either[CreationError, `type`.TYPE] = {
    Try(JsonPath.compile(jsonPathStr)) match {
      case Success(compiledPath) =>
        Right(`type`.createJwtVariableExtractable(compiledPath))
      case Failure(ex) =>
        logger.debug("Compiling JSON path failed", ex)
        Left(CreationError(s"Cannot create JWT variable, because cannot compile '$jsonPathStr' to JsonPath"))
    }
  }

  private def createHeaderExtractable(headerNameStr: String, `type`: ExtractableType): Either[CreationError, `type`.TYPE] = {
    NonEmptyString.unapply(headerNameStr) match {
      case Some(nes) =>
        Right(`type`.createHeaderVariableExtractable(Header.Name(nes)))
      case None =>
        Left(CreationError(s"Cannot create header variable, because no header name is passed"))
    }
  }

  private object regexes {
    val userVar: Regex = "user".r
    val explicitHeaderVar: Regex = "header:(.*)".r
    val jwtPayloadPathVar: Regex = "jwt:(.*)".r
  }

  private sealed trait ExtractableType {
    type TYPE <: Extractable[_]
    def createConstExtractable(value: String): TYPE
    def createUserIdExtractable(): TYPE
    def createJwtVariableExtractable(jsonPath: JsonPath): TYPE
    def createHeaderVariableExtractable(header: Header.Name): TYPE
  }
  private object ExtractableType {
    case object Single extends ExtractableType {
      override type TYPE = SingleExtractable

      override def createConstExtractable(value: String): TYPE = SingleExtractable.Const(value)
      override def createUserIdExtractable(): TYPE = SingleExtractable.UserIdVar
      override def createJwtVariableExtractable(jsonPath: JsonPath): TYPE = SingleExtractable.JwtPayloadVar(jsonPath)
      override def createHeaderVariableExtractable(header: Header.Name): TYPE = SingleExtractable.HeaderVar(header)
    }
    case object Multi extends ExtractableType {
      override type TYPE = MultiExtractable

      override def createConstExtractable(value: String): TYPE = MultiExtractable.Const(value)
      override def createUserIdExtractable(): TYPE = MultiExtractable.UserIdVar
      override def createJwtVariableExtractable(jsonPath: JsonPath): TYPE = MultiExtractable.JwtPayloadVar(jsonPath)
      override def createHeaderVariableExtractable(header: Header.Name): TYPE = MultiExtractable.HeaderVar(header)
    }
  }

  private final case class Acc(results: Vector[Either[CreationError, MultiExtractable]],
                               multiExtractableCount: Int)
}
