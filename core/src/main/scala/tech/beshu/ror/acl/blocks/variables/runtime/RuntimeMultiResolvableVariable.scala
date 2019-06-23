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
import cats.data.NonEmptyList
import cats.implicits._
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.variables.runtime.Extractable.ExtractError
import tech.beshu.ror.acl.blocks.variables.runtime.Variable.ConvertError
import tech.beshu.ror.acl.domain.{ClaimName, Header}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.utils.ClaimsOps.ClaimSearchResult.{Found, NotFound}
import tech.beshu.ror.acl.utils.ClaimsOps._
import tech.beshu.ror.com.jayway.jsonpath.JsonPath

trait RuntimeMultiResolvableVariable[T] extends Variable[NonEmptyList[T]]
object RuntimeMultiResolvableVariable {
  final case class AlreadyResolved[T](value: NonEmptyList[T])
    extends Variable.AlreadyResolved(value)
    with RuntimeMultiResolvableVariable[T]

  final case class ToBeResolved[T](values: List[MultiExtractable],
                                   convert: String => Either[ConvertError, T])
    extends Variable.ToBeResolved(
      values,
      (items: List[String]) =>
        items
          .map(convert)
          .sequence
          .flatMap { result =>
            NonEmptyList.fromList(result) match {
              case Some(nel) => Right(nel)
              case None => Left(ConvertError("Resolved values list is empty"))
            }
          }
    ) with RuntimeMultiResolvableVariable[T]

  implicit def runtimeResolvableOrder[T : Order]: Order[RuntimeMultiResolvableVariable[T]] =
    Order.from {
      case (AlreadyResolved(c1), AlreadyResolved(c2)) => c1 compare c2
      case (AlreadyResolved(_), _) => -1
      case (_, AlreadyResolved(_)) => 1
      case (v1, v2) => v1.hashCode() compareTo v2.hashCode()
    }

}

sealed trait MultiExtractable extends Extractable[List[String]]

object MultiExtractable {

  final case class Const(value: String) extends MultiExtractable {
    private val singleConst = SingleExtractable.Const(value)
    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, List[String]] =
      singleConst
        .extractUsing(requestContext, blockContext)
        .map(List(_))
  }

  case object UserIdVar extends MultiExtractable {
    private val singleUserIdVar = SingleExtractable.UserIdVar
    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, List[String]] = {
      singleUserIdVar
        .extractUsing(requestContext, blockContext)
        .map(List(_))
    }
  }

  final case class HeaderVar(header: Header.Name) extends MultiExtractable {
    private val singleHeaderVar = SingleExtractable.HeaderVar(header)
    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, List[String]] = {
      singleHeaderVar
        .extractUsing(requestContext, blockContext)
        .map(List(_))
    }
  }

  final case class JwtPayloadVar(jsonPath: JsonPath) extends MultiExtractable {
    private val varClaim = ClaimName(jsonPath)

    override def extractUsing(requestContext: RequestContext,
                              blockContext: BlockContext): Either[ExtractError, List[String]] = ??? // todo: impl
  }

}
