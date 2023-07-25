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
package tech.beshu.ror.accesscontrol.blocks.variables.runtime

import cats.data.NonEmptyList
import cats.instances.either._
import cats.instances.list._
import cats.syntax.traverse._
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.variables.Tokenizer.Token
import tech.beshu.ror.accesscontrol.blocks.variables.Tokenizer.Token.Transformation
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.MultiExtractable.SingleExtractableWrapper
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler.CompilationError
import tech.beshu.ror.accesscontrol.blocks.variables.{Tokenizer, VariableCreationConfig, runtime}
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.com.jayway.jsonpath.JsonPath
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.Function

import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

object RuntimeResolvableVariableCreator extends Logging {

  sealed trait CreationError
  object CreationError {
    case object CannotUserMultiVariableInSingleVariableContext extends CreationError
    case object OnlyOneMultiVariableCanBeUsedInVariableDefinition extends CreationError
    final case class InvalidVariableDefinition(cause: String) extends CreationError
    final case class VariableConversionError(msg: String) extends CreationError
  }

  def createSingleResolvableVariableFrom[T: Convertible](text: NonEmptyString)
                                                        (implicit config: VariableCreationConfig): Either[CreationError, RuntimeSingleResolvableVariable[T]] = {
    singleExtactablesFrom(Tokenizer.tokenize(text))
      .flatMap(variableFromSingleExtractables[T])
  }

  def createMultiResolvableVariableFrom[T: Convertible](text: NonEmptyString)
                                                       (implicit config: VariableCreationConfig): Either[CreationError, RuntimeMultiResolvableVariable[T]] = {
    multiExtractablesFrom(Tokenizer.tokenize(text))
      .flatMap(variableFromMultiExtractables[T])
  }

  private def singleExtactablesFrom(tokens: NonEmptyList[Token])
                                   (implicit config: VariableCreationConfig): Either[CreationError, NonEmptyList[SingleExtractable]] = {
    tokens
      .map {
        case Token.Text(value) =>
          Right(ExtractableType.Single.createConstExtractable(value))
        case Token.Placeholder(name, _, transformation) =>
          createExtractableWithTransformation(name, transformation, ExtractableType.Single)
        case Token.ExplodablePlaceholder(_, _, _) =>
          Left(CreationError.CannotUserMultiVariableInSingleVariableContext)
      }
      .sequence
  }

  private def variableFromSingleExtractables[T: Convertible](extractables: NonEmptyList[SingleExtractable]) = {
    val alreadyResolved = extractables.collect { case c: SingleExtractable.Const => c }
    if (alreadyResolved.length == extractables.length) {
      implicitly[Convertible[T]].convert(alreadyResolved.map(_.value).foldLeft("")(_ + _))
        .map(value => RuntimeSingleResolvableVariable.AlreadyResolved(value))
        .left.map(e => CreationError.VariableConversionError(e.msg))
    } else {
      Right(RuntimeSingleResolvableVariable.ToBeResolved[T](extractables))
    }
  }

  private def multiExtractablesFrom(tokens: NonEmptyList[Token])
                                   (implicit config: VariableCreationConfig): Either[CreationError, NonEmptyList[MultiExtractable]] = {
    val acc = tokens.foldLeft(Acc(Vector.empty, 0)) {
      case (Acc(results, multiExtractableCount), token) => token match {
        case Token.Text(value) =>
          Acc(results :+ Right(ExtractableType.Multi.createConstExtractable(value)), multiExtractableCount)
        case Token.Placeholder(name, _, transformation) =>
          val extractable = createExtractableWithTransformation(name, transformation, ExtractableType.Single)
            .map(SingleExtractableWrapper.apply)
          Acc(results :+ extractable, multiExtractableCount)
        case Token.ExplodablePlaceholder(name, _, transformation) =>
          val extractable = createExtractableWithTransformation(name, transformation, ExtractableType.Multi)
          Acc(results :+ extractable, multiExtractableCount + 1)
      }
    }
    if (acc.multiExtractableCount <= 1) {
      acc.results.toList.sequence.map(NonEmptyList.fromListUnsafe)
    } else {
      Left(CreationError.OnlyOneMultiVariableCanBeUsedInVariableDefinition)
    }
  }

  private def variableFromMultiExtractables[T: Convertible](extractables: NonEmptyList[MultiExtractable]) = {
    val alreadyResolved = extractables.collect { case c: runtime.MultiExtractable.Const => c }
    if (alreadyResolved.length == extractables.length) {
      implicitly[Convertible[T]].convert(alreadyResolved.map(_.value).foldLeft("")(_ + _))
        .map(value => RuntimeMultiResolvableVariable.AlreadyResolved(NonEmptyList.one(value)))
        .left.map(e => CreationError.VariableConversionError(e.msg))
    } else {
      Right(RuntimeMultiResolvableVariable.ToBeResolved[T](extractables))
    }
  }

  private def createExtractableWithTransformation(value: String,
                                                  maybeTransformation: Option[Transformation],
                                                  `type`: ExtractableType)
                                                 (implicit config: VariableCreationConfig): Either[CreationError, `type`.TYPE] = {
    for {
      extractable <- createExtractableFromToken(value, `type`)
      finalExtractable <-
        maybeTransformation
          .map { transformation =>
            compile(transformation).map(`type`.createTransformedExtractable(extractable, _))
          }
          .getOrElse(Right(extractable))
    } yield finalExtractable
  }

  private def createExtractableFromToken[VALUE](value: String, `type`: ExtractableType): Either[CreationError, `type`.TYPE] = {
    value match {
      case regexes.userVar() =>
        Right(`type`.createUserIdExtractable())
      case regexes.userWithNamespaceVar() =>
        Right(`type`.createUserIdExtractable())
      case regexes.jwtPayloadPathVar(path) =>
        createJwtExtractable(path, `type`)
      case regexes.explicitHeaderVar(headerName) =>
        createHeaderExtractable(headerName, `type`)
      case regexes.currentGroupVar() =>
        Right(`type`.createCurrentGroupExtractable())
      case regexes.availableGroupsVar() =>
        Right(`type`.createAvailableGroupsExtractable())
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
        Left(CreationError.InvalidVariableDefinition(s"cannot compile '$jsonPathStr' to JsonPath"))
    }
  }

  private def createHeaderExtractable(headerNameStr: String, `type`: ExtractableType): Either[CreationError, `type`.TYPE] = {
    NonEmptyString.unapply(headerNameStr) match {
      case Some(nes) =>
        Right(`type`.createHeaderVariableExtractable(Header.Name(nes)))
      case None =>
        Left(CreationError.InvalidVariableDefinition(s"No header name is passed"))
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
    val userVar: Regex = "user".r
    val userWithNamespaceVar: Regex = "acl:user".r
    val explicitHeaderVar: Regex = "header:(.*)".r
    val jwtPayloadPathVar: Regex = "jwt:(.*)".r
    val currentGroupVar: Regex = "acl:current_group".r
    val availableGroupsVar: Regex = "acl:available_groups".r
  }

  private sealed trait ExtractableType {

    type TYPE <: Extractable[_]
    def createConstExtractable(value: String): TYPE
    def createUserIdExtractable(): TYPE
    def createJwtVariableExtractable(jsonPath: JsonPath): TYPE
    def createHeaderVariableExtractable(header: Header.Name): TYPE
    def createCurrentGroupExtractable(): TYPE
    def createAvailableGroupsExtractable(): TYPE
    def createTransformedExtractable(extractable: TYPE, transformation: Function): TYPE
  }
  private object ExtractableType {
    case object Single extends ExtractableType {
      override type TYPE = SingleExtractable

      override def createConstExtractable(value: String): TYPE = SingleExtractable.Const(value)
      override def createUserIdExtractable(): TYPE = SingleExtractable.UserIdVar
      override def createJwtVariableExtractable(jsonPath: JsonPath): TYPE = SingleExtractable.JwtPayloadVar(jsonPath)
      override def createHeaderVariableExtractable(header: Header.Name): TYPE = SingleExtractable.HeaderVar(header)
      override def createCurrentGroupExtractable(): TYPE = SingleExtractable.CurrentGroupVar
      override def createAvailableGroupsExtractable(): TYPE = SingleExtractable.AvailableGroupsVar
      override def createTransformedExtractable(extractable: SingleExtractable,
                                                transformation: Function): SingleExtractable =
        new SingleExtractable.TransformationApplyingExtractableDecorator(extractable, transformation)
    }
    case object Multi extends ExtractableType {
      override type TYPE = MultiExtractable

      override def createConstExtractable(value: String): TYPE = MultiExtractable.Const(value)
      override def createUserIdExtractable(): TYPE = MultiExtractable.UserIdVar
      override def createJwtVariableExtractable(jsonPath: JsonPath): TYPE = MultiExtractable.JwtPayloadVar(jsonPath)
      override def createHeaderVariableExtractable(header: Header.Name): TYPE = MultiExtractable.HeaderVar(header)
      override def createCurrentGroupExtractable(): TYPE = MultiExtractable.CurrentGroupVar
      override def createAvailableGroupsExtractable(): TYPE = MultiExtractable.AvailableGroupsVar
      override def createTransformedExtractable(extractable: MultiExtractable,
                                                transformation: Function): MultiExtractable =
        new MultiExtractable.TransformationApplyingExtractableDecorator(extractable, transformation)
    }
  }

  private final case class Acc(results: Vector[Either[CreationError, MultiExtractable]],
                               multiExtractableCount: Int)
}
