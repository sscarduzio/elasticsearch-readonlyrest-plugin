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

import cats.Order
import cats.implicits._
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.variables.runtime.Extractable.ExtractError
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariable.ConvertError
import tech.beshu.ror.acl.domain.{ClaimName, Header}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.utils.ClaimsOps.ClaimSearchResult.{Found, NotFound}
import tech.beshu.ror.acl.utils.ClaimsOps._
import tech.beshu.ror.com.jayway.jsonpath.JsonPath

trait RuntimeSingleResolvableVariable[T] extends RuntimeResolvableVariable[T]
object RuntimeSingleResolvableVariable {
  final case class AlreadyResolved[T](value: T)
    extends RuntimeResolvableVariable.AlreadyResolved(value)
      with RuntimeSingleResolvableVariable[T]

  final case class ToBeResolved[T](values: List[SingleExtractable],
                                   convert: String => Either[ConvertError, T])
    extends RuntimeResolvableVariable.ToBeResolved(values, convert)
      with RuntimeSingleResolvableVariable[T]

  implicit def runtimeResolvableOrder[T : Order]: Order[RuntimeSingleResolvableVariable[T]] =
    Order.from {
      case (AlreadyResolved(c1), AlreadyResolved(c2)) => c1 compare c2
      case (AlreadyResolved(_), _) => -1
      case (_, AlreadyResolved(_)) => 1
      case (v1, v2) => v1.hashCode() compareTo v2.hashCode()
    }

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
              Right(values.head)
            case NotFound =>
              Left(ExtractError(s"Cannot find value string or collection of strings in path '${jsonPath.show}' of JWT Token"))
          }
        case None =>
          Left(ExtractError("Cannot extract JSON token payload from block context"))
      }
    }
  }
}
