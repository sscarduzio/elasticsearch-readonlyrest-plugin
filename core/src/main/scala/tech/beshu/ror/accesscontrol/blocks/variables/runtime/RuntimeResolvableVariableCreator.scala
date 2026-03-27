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
import cats.instances.either.*
import cats.instances.list.*
import cats.syntax.traverse.*
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.accesscontrol.blocks.variables.Tokenizer.Token
import tech.beshu.ror.accesscontrol.blocks.variables.Tokenizer.Token.Transformation
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.MultiExtractable.SingleExtractableWrapper
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator.*
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler.CompilationError
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.Function
import tech.beshu.ror.accesscontrol.blocks.variables.{Tokenizer, runtime}
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.json.JsonPath

import scala.util.matching.Regex
import scala.util.{Failure, Success}

class RuntimeResolvableVariableCreator(transformationCompiler: TransformationCompiler) extends RequestIdAwareLogging {

  def createSingleResolvableVariableFrom[T: Convertible](text: NonEmptyString): Either[CreationError, RuntimeSingleResolvableVariable[T]] = {
    singleExtactablesFrom(Tokenizer.tokenize(text))
      .flatMap(variableFromSingleExtractables[T])
  }

  def createMultiResolvableVariableFrom[T: Convertible](text: NonEmptyString): Either[CreationError, RuntimeMultiResolvableVariable[T]] = {
    multiExtractablesFrom(Tokenizer.tokenize(text))
      .flatMap(variableFromMultiExtractables[T])
  }

  private def singleExtactablesFrom(tokens: NonEmptyList[Token]): Either[CreationError, NonEmptyList[SingleExtractable]] = {
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

  private def multiExtractablesFrom(tokens: NonEmptyList[Token]): Either[CreationError, NonEmptyList[MultiExtractable]] = {
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
                                                  `type`: ExtractableType): Either[CreationError, `type`.TYPE] = {
    for {
      transformation <- maybeTransformation.map(compile(_).map(Some.apply)).getOrElse(Right(None))
      extractable <- createExtractableFromToken(value, transformation, `type`)
    } yield extractable
  }

  private def createExtractableFromToken(value: String,
                                         maybeTransformation: Option[Function],
                                         `type`: ExtractableType): Either[CreationError, `type`.TYPE] = {
    value match {
      case regexes.userVar() =>
        Right(`type`.createUserIdExtractable(maybeTransformation))
      case regexes.userWithNamespaceVar() =>
        Right(`type`.createUserIdExtractable(maybeTransformation))
      case regexes.jwtPayloadPathVar(path) =>
        createJwtExtractable(path, maybeTransformation, `type`)
      case regexes.explicitHeaderVar(headerName) =>
        createHeaderExtractable(headerName, maybeTransformation, `type`)
      case regexes.currentGroupVar() =>
        Right(`type`.createCurrentGroupExtractable(maybeTransformation))
      case regexes.availableGroupsVar() =>
        Right(`type`.createAvailableGroupsExtractable(maybeTransformation))
      case other => // backward compatibility - assuming that it's header
        createHeaderExtractable(other, maybeTransformation: Option[Function], `type`)
    }
  }

  private def createJwtExtractable(jsonPathStr: String, maybeTransformation: Option[Function], `type`: ExtractableType): Either[CreationError, `type`.TYPE] = {
    JsonPath(jsonPathStr) match {
      case Success(compiledPath) =>
        Right(`type`.createJwtVariableExtractable(compiledPath, maybeTransformation))
      case Failure(ex) =>
        noRequestIdLogger.debug("Compiling JSON path failed", ex)
        Left(CreationError.InvalidVariableDefinition(s"cannot compile '${jsonPathStr.show}' to JsonPath"))
    }
  }

  private def createHeaderExtractable(headerNameStr: String,
                                      maybeTransformation: Option[Function],
                                      `type`: ExtractableType): Either[CreationError, `type`.TYPE] = {
    NonEmptyString.unapply(headerNameStr) match {
      case Some(nes) =>
        Right(`type`.createHeaderVariableExtractable(Header.Name(nes), maybeTransformation))
      case None =>
        Left(CreationError.InvalidVariableDefinition(s"No header name is passed"))
    }
  }

  private def compile(transformation: Token.Transformation) = {
    transformationCompiler
      .compile(transformation.name)
      .left.map(toCreationError(transformation.name, _))
  }

  private def toCreationError(transformationStr: String,
                              error: TransformationCompiler.CompilationError): CreationError = error match {
    case CompilationError.UnableToParseTransformation(message) =>
      CreationError.InvalidVariableDefinition(s"Unable to parse transformation string: [${transformationStr.show}]. Cause: ${message.show}")
    case CompilationError.UnableToCompileTransformation(message) =>
      CreationError.InvalidVariableDefinition(s"Unable to compile transformation string: [${transformationStr.show}]. Cause: ${message.show}")
  }
}

object RuntimeResolvableVariableCreator {

  sealed trait CreationError
  object CreationError {
    case object CannotUserMultiVariableInSingleVariableContext extends CreationError
    case object OnlyOneMultiVariableCanBeUsedInVariableDefinition extends CreationError
    final case class InvalidVariableDefinition(cause: String) extends CreationError
    final case class VariableConversionError(msg: String) extends CreationError
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
    def createUserIdExtractable(transformation: Option[Function]): TYPE
    def createJwtVariableExtractable(jsonPath: JsonPath, transformation: Option[Function]): TYPE
    def createHeaderVariableExtractable(header: Header.Name, transformation: Option[Function]): TYPE
    def createCurrentGroupExtractable(transformation: Option[Function]): TYPE
    def createAvailableGroupsExtractable(transformation: Option[Function]): TYPE
  }

  private object ExtractableType {
    case object Single extends ExtractableType {
      override type TYPE = SingleExtractable
      override def createConstExtractable(value: String): TYPE = SingleExtractable.Const(value)
      override def createUserIdExtractable(transformation: Option[Function]): TYPE =
        new SingleExtractable.UserIdVar(transformation)
      override def createJwtVariableExtractable(jsonPath: JsonPath, transformation: Option[Function]): TYPE =
        new SingleExtractable.JwtPayloadVar(jsonPath, transformation)
      override def createHeaderVariableExtractable(header: Header.Name, transformation: Option[Function]): TYPE =
        new SingleExtractable.HeaderVar(header, transformation)
      override def createCurrentGroupExtractable(transformation: Option[Function]): TYPE =
        new SingleExtractable.CurrentGroupVar(transformation)
      override def createAvailableGroupsExtractable(transformation: Option[Function]): TYPE =
        new SingleExtractable.AvailableGroupsVar(transformation)
    }

    case object Multi extends ExtractableType {
      override type TYPE = MultiExtractable
      override def createConstExtractable(value: String): TYPE = MultiExtractable.Const(value)
      override def createUserIdExtractable(transformation: Option[Function]): TYPE =
        new MultiExtractable.UserIdVar(transformation)
      override def createJwtVariableExtractable(jsonPath: JsonPath, transformation: Option[Function]): TYPE =
        new MultiExtractable.JwtPayloadVar(jsonPath, transformation)
      override def createHeaderVariableExtractable(header: Header.Name, transformation: Option[Function]): TYPE =
        new MultiExtractable.HeaderVar(header, transformation)
      override def createCurrentGroupExtractable(transformation: Option[Function]): TYPE =
        new MultiExtractable.CurrentGroupVar(transformation)
      override def createAvailableGroupsExtractable(transformation: Option[Function]): TYPE =
        new MultiExtractable.AvailableGroupsVar(transformation)
    }
  }

  private final case class Acc(results: Vector[Either[CreationError, MultiExtractable]],
                               multiExtractableCount: Int)
}
