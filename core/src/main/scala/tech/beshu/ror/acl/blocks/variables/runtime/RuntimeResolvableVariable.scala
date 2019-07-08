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
package tech.beshu.ror.acl.blocks.variables.runtime

import cats.data.NonEmptyList
import cats.implicits._
import com.github.tototoshi.csv.{CSVParser, DefaultCSVFormat}
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.variables.runtime.Extractable.ExtractError
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.ConvertError
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariable.Unresolvable
import tech.beshu.ror.acl.domain.{ClaimName, Header}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.utils.ClaimsOps.ClaimSearchResult.{Found, NotFound}
import tech.beshu.ror.acl.utils.ClaimsOps._
import tech.beshu.ror.com.jayway.jsonpath.JsonPath

private [runtime] trait RuntimeResolvableVariable[VALUE] {

  def resolve(requestContext: RequestContext,
              blockContext: BlockContext): Either[Unresolvable, VALUE]
}

object RuntimeResolvableVariable {

  sealed trait Unresolvable
  object Unresolvable {
    final case class CannotExtractValue(msg: String) extends Unresolvable
    final case class CannotInstantiateResolvedValue(msg: String) extends Unresolvable
  }

  trait Convertible[T] {
    def convert: String => Either[ConvertError, T]
  }
  object Convertible {
    final case class ConvertError(msg: String)

    trait AlwaysRightConvertible[T] extends Convertible[T] {
      def safeConvert: String => T
      override def convert: String => Either[ConvertError, T] =
        safeConvert andThen (Right(_))
    }
    object AlwaysRightConvertible {
      def from[T](conversion: String => T): AlwaysRightConvertible[T] = new AlwaysRightConvertible[T] {
        override def safeConvert: String => T = conversion
      }
      val stringAlwaysRightConvertible: AlwaysRightConvertible[String] = new AlwaysRightConvertible[String] {
        override def safeConvert: String => String = identity
      }
    }
  }
}

sealed trait Extractable[VALUE] {

  def extractUsing(requestContext: RequestContext,
                   blockContext: BlockContext): Either[ExtractError, VALUE]
}
object Extractable {

  final case class ExtractError(msg: String) extends AnyVal

}

sealed trait SingleExtractable extends Extractable[String]
object SingleExtractable {

  final case class Const(value: String) extends SingleExtractable {
    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, String] = Right(value)
  }

  case object UserIdVar extends SingleExtractable {
    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, String] = {
      blockContext
        .loggedUser
        .map(_.id.value) match {
        case Some(value) => Right(value)
        case None => Left(ExtractError("Cannot extract user ID from block context"))
      }
    }
  }

  final case class HeaderVar(header: Header.Name) extends SingleExtractable {
    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, String] = {
      requestContext
        .headers
        .find(_.name === header)
        .map(_.value.value) match {
        case Some(value) => Right(value)
        case None => Left(ExtractError(s"Cannot extract user header '${header.show}' from request context"))
      }
    }
  }

  final case class JwtPayloadVar(jsonPath: JsonPath) extends SingleExtractable {
    private val varClaim = ClaimName(jsonPath)

    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, String] = {
      blockContext.jsonToken match {
        case Some(payload) =>
          payload.claims.customClaim(varClaim) match {
            case Found(values) =>
              Right(values.toList.mkString(","))
            case NotFound =>
              Left(ExtractError(s"Cannot find value string or collection of strings in path '${jsonPath.show}' of JWT Token"))
          }
        case None =>
          Left(ExtractError("Cannot extract JSON token payload from block context"))
      }
    }
  }
}

sealed trait MultiExtractable extends Extractable[NonEmptyList[String]] {
  private val csvParser =  new CSVParser(new DefaultCSVFormat {})

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
    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, NonEmptyList[String]] =
      extractable.extractUsing(requestContext, blockContext).map(NonEmptyList.one)
  }

  final case class Const(value: String) extends MultiExtractable {
    private val singleConst = SingleExtractable.Const(value)
    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, NonEmptyList[String]] =
      singleConst
        .extractUsing(requestContext, blockContext)
        .map(NonEmptyList.one)
  }

  case object UserIdVar extends MultiExtractable {
    private val singleUserIdExtractable = SingleExtractable.UserIdVar

    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, NonEmptyList[String]] = {
      singleUserIdExtractable
        .extractUsing(requestContext, blockContext)
        .map(parseCsvValue)
    }
  }

  final case class HeaderVar(header: Header.Name) extends MultiExtractable {
    private val singleHeaderExtractable = SingleExtractable.HeaderVar(header)
    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, NonEmptyList[String]] = {
      singleHeaderExtractable
        .extractUsing(requestContext, blockContext)
        .map(parseCsvValue)
    }
  }

  final case class JwtPayloadVar(jsonPath: JsonPath) extends MultiExtractable {
    private val singleJwtPayloadExtractable = SingleExtractable.JwtPayloadVar(jsonPath)

    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, NonEmptyList[String]] = {
      singleJwtPayloadExtractable
        .extractUsing(requestContext, blockContext)
        .map(parseCsvValue)
    }
  }

}
