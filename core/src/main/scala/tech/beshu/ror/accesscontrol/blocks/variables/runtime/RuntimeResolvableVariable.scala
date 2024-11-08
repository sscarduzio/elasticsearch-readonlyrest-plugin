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
import com.github.tototoshi.csv.{CSVParser, DefaultCSVFormat}
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.Extractable.ExtractError
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.ConvertError
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Unresolvable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.VariableType
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.domain.Function
import tech.beshu.ror.accesscontrol.domain.{Header, Jwt}
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.*
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult.{Found, NotFound}
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.CustomClaimValue.{CollectionValue, SingleValue}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.json.JsonPath

private[runtime] trait RuntimeResolvableVariable[VALUE] {

  def resolve(blockContext: BlockContext): Either[Unresolvable, VALUE]
}

object RuntimeResolvableVariable {

  sealed trait Unresolvable extends Throwable
  object Unresolvable {
    final case class CannotExtractValue(override val getMessage: String) extends Unresolvable
    final case class CannotInstantiateResolvedValue(override val getMessage: String) extends Unresolvable
  }

  trait Convertible[T] {
    def convert: String => Either[ConvertError, T]
  }
  object Convertible {
    final case class ConvertError(msg: String)

    trait AlwaysRightConvertible[T] extends Convertible[T] {
      def safeConvert: NonEmptyString => T

      override def convert: String => Either[ConvertError, T] = str => {
        NonEmptyString
          .from(str)
          .map(safeConvert)
          .left.map(_ => ConvertError("Cannot use empty string"))
      }
    }
    object AlwaysRightConvertible {
      def from[T](conversion: NonEmptyString => T): AlwaysRightConvertible[T] = new AlwaysRightConvertible[T] {
        override def safeConvert: NonEmptyString => T = conversion
      }

      val stringAlwaysRightConvertible: AlwaysRightConvertible[String] = new AlwaysRightConvertible[String] {
        override def safeConvert: NonEmptyString => String = _.value
      }
    }
  }
}

sealed trait Extractable[VALUE] {

  def extractUsing(blockContext: BlockContext): Either[ExtractError, VALUE]
}
object Extractable {

  final case class ExtractError(msg: String) extends AnyVal

}

sealed trait SingleExtractable extends Extractable[String]
object SingleExtractable {

  final case class Const(value: String) extends SingleExtractable {
    override def extractUsing(blockContext: BlockContext): Either[ExtractError, String] = Right(value)
  }

  final class UserIdVar(transformation: Option[Function]) extends SingleExtractable with VariableType.User {
    override def extractUsing(blockContext: BlockContext): Either[ExtractError, String] = withTransformation(transformation) {
      blockContext
        .userMetadata
        .loggedUser
        .map(_.id.value) match {
        case Some(value) => Right(value.value)
        case None => Left(ExtractError("Cannot extract user ID from block context"))
      }
    }
  }

  final class HeaderVar(header: Header.Name, transformation: Option[Function]) extends SingleExtractable with VariableType.Header {
    override def extractUsing(blockContext: BlockContext): Either[ExtractError, String] = withTransformation(transformation) {
      blockContext
        .requestContext
        .headers
        .find(_.name === header)
        .map(_.value.value) match {
        case Some(value) => Right(value)
        case None => Left(ExtractError(s"Cannot extract user header '${header.show}' from request context"))
      }
    }
  }

  final class JwtPayloadVar(jsonPath: JsonPath, transformation: Option[Function]) extends SingleExtractable with VariableType.Jwt {
    private val varClaim = Jwt.ClaimName(jsonPath)

    override def extractUsing(blockContext: BlockContext): Either[ExtractError, String] = withTransformation(transformation) {
      blockContext.userMetadata.jwtToken match {
        case Some(payload) =>
          payload.claims.customClaim(varClaim) match {
            case Found(SingleValue(value)) =>
              Right(value)
            case Found(CollectionValue(values)) =>
              Right(values.toList.map(v => s""""$v"""").mkString(","))
            case NotFound =>
              Left(ExtractError(s"Cannot find value string or collection of strings in path '${jsonPath.show}' of JWT Token"))
          }
        case None =>
          Left(ExtractError("Cannot extract JSON token payload from block context"))
      }
    }
  }

  final class CurrentGroupVar(transformation: Option[Function]) extends SingleExtractable with VariableType.CurrentGroup {

    override def extractUsing(blockContext: BlockContext): Either[ExtractError, String] = withTransformation(transformation) {
      blockContext
        .userMetadata
        .currentGroupId
        .map(_.value.value) match {
        case Some(value) => Right(value)
        case None => Left(ExtractError(s"There was no current group for request: ${blockContext.requestContext.id.show}"))
      }
    }
  }

  final class AvailableGroupsVar(transformation: Option[Function]) extends SingleExtractable with VariableType.AvailableGroups {

    override def extractUsing(blockContext: BlockContext): Either[ExtractError, String] = withTransformation(transformation) {
      Right(blockContext.userMetadata.availableGroups.toList.map(v => s""""${v.id.value.value}"""").mkString(","))
    }
  }

  private def withTransformation(maybeFunction: Option[Function])(extractable: => Either[ExtractError, String]) = {
    maybeFunction match {
      case Some(function) => extractable.map(function.apply)
      case None => extractable
    }
  }
}

sealed trait MultiExtractable extends Extractable[NonEmptyList[String]] {
  private val csvParser = new CSVParser(new DefaultCSVFormat {})

  protected def parseCsvValue(csvValue: String): NonEmptyList[String] = {
    (for {
      values <- csvParser.parseLine(csvValue)
      result <- NonEmptyList.fromList(values)
    } yield result) match {
      case Some(value) => value
      case None => NonEmptyList.one("")
    }
  }
}
object MultiExtractable {

  final case class SingleExtractableWrapper(extractable: SingleExtractable) extends MultiExtractable {
    override def extractUsing(blockContext: BlockContext): Either[ExtractError, NonEmptyList[String]] =
      extractable.extractUsing(blockContext).map(NonEmptyList.one)
  }

  final case class Const(value: String) extends MultiExtractable {
    private val singleConst = SingleExtractable.Const(value)

    override def extractUsing(blockContext: BlockContext): Either[ExtractError, NonEmptyList[String]] =
      singleConst
        .extractUsing(blockContext)
        .map(NonEmptyList.one)
  }

  final class UserIdVar(transformation: Option[Function]) extends MultiExtractable with VariableType.User {
    private val singleUserIdExtractable = new SingleExtractable.UserIdVar(transformation)

    override def extractUsing(blockContext: BlockContext): Either[ExtractError, NonEmptyList[String]] =
      singleUserIdExtractable
        .extractUsing(blockContext)
        .map(NonEmptyList.one)
  }

  final class HeaderVar(header: Header.Name,
                        transformation: Option[Function]) extends MultiExtractable with VariableType.Header {
    private val singleHeaderExtractable = new SingleExtractable.HeaderVar(header, transformation)

    override def extractUsing(blockContext: BlockContext): Either[ExtractError, NonEmptyList[String]] =
      singleHeaderExtractable
        .extractUsing(blockContext)
        .map(parseCsvValue)

  }

  final class JwtPayloadVar(jsonPath: JsonPath,
                            transformation: Option[Function]) extends MultiExtractable with VariableType.Jwt {
    private val singleJwtPayloadExtractable = new SingleExtractable.JwtPayloadVar(jsonPath, transformation)

    override def extractUsing(blockContext: BlockContext): Either[ExtractError, NonEmptyList[String]] =
      singleJwtPayloadExtractable
        .extractUsing(blockContext)
        .map(parseCsvValue)
  }

  final class CurrentGroupVar(transformation: Option[Function]) extends MultiExtractable with VariableType.CurrentGroup {
    private val singleCurrentGroupExtractable = new SingleExtractable.CurrentGroupVar(transformation)

    override def extractUsing(blockContext: BlockContext): Either[ExtractError, NonEmptyList[String]] =
      singleCurrentGroupExtractable
        .extractUsing(blockContext)
        .map(NonEmptyList.one)
  }

  final class AvailableGroupsVar(transformation: Option[Function]) extends MultiExtractable with VariableType.AvailableGroups {
    override def extractUsing(blockContext: BlockContext): Either[ExtractError, NonEmptyList[String]] = {
      withTransformation {
        NonEmptyList
          .fromList(
            blockContext
              .userMetadata
              .availableGroups
              .map(_.id.value.value)
              .toList
          )
          .toRight(ExtractError(s"There were no groups for request: ${blockContext.requestContext.id.show}"))
      }
    }

    private def withTransformation(extractable: => Either[ExtractError, NonEmptyList[String]]) = transformation match {
      case Some(function) => extractable.map(_.map(function.apply))
      case None => extractable
    }
  }
}
