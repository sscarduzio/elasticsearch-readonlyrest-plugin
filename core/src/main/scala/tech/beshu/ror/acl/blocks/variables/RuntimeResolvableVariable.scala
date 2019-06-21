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
package tech.beshu.ror.acl.blocks.variables

import cats.Order
import cats.implicits._
import cats.instances.list._
import cats.instances.either._
import cats.syntax.traverse._
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.variables.Extractable.ExtractError
import tech.beshu.ror.acl.blocks.variables.RuntimeResolvableVariable.Unresolvable.{CannotExtractValue, CannotInstantiateResolvedValue}
import tech.beshu.ror.acl.blocks.variables.RuntimeResolvableVariable.{ConvertError, Unresolvable}
import tech.beshu.ror.acl.domain.Header
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.com.jayway.jsonpath.JsonPath

sealed trait RuntimeResolvableVariable[T] {

  def resolve(requestContext: RequestContext,
              blockContext: BlockContext): Either[Unresolvable, T]
}

object RuntimeResolvableVariable {

  final case class ConvertError(value: String, msg: String)

  sealed trait Unresolvable
  object Unresolvable {
    final case class CannotExtractValue(msg: String) extends Unresolvable
    final case class CannotInstantiateResolvedValue(msg: String) extends Unresolvable
  }

  implicit def runtimeResolvableOrder[T : Order]: Order[RuntimeResolvableVariable[T]] =
    Order.from {
      case (AlreadyResolved(c1), AlreadyResolved(c2)) => c1 compare c2
      case (AlreadyResolved(_), _) => -1
      case (_, AlreadyResolved(_)) => 1
      case (v1, v2) => v1.hashCode() compareTo v2.hashCode()
    }
}

final case class AlreadyResolved[T](value: T)
  extends RuntimeResolvableVariable[T] {

  override def resolve(requestContext: RequestContext,
                       blockContext: BlockContext): Either[Unresolvable, T] =
    Right(value)
}

final case class ToBeResolved[T](values: List[Extractable],
                                 convert: String => Either[ConvertError, T])
  extends RuntimeResolvableVariable[T] {

  override def resolve(requestContext: RequestContext,
                       blockContext: BlockContext): Either[Unresolvable, T] = {
    values
      .foldLeft(Either.right[Unresolvable, String]("")) {
        case (Right(accumulator), value) =>
          value
            .extractUsing(requestContext, blockContext)
            .left.map(error => CannotExtractValue(error.msg))
            .map(extracted => s"$accumulator$extracted")
        case (left@Left(_), _) =>
          left
      }
      .flatMap { resolved =>
        convert(resolved).left.map(error => CannotInstantiateResolvedValue(error.msg))
      }
  }
}

sealed trait Extractable {
  def extractUsing(requestContext: RequestContext,
                   blockContext: BlockContext): Either[ExtractError, String]
}

object Extractable {
  final case class ExtractError(msg: String) extends AnyVal
}

final case class Const(value: String) extends Extractable {
  override def extractUsing(requestContext: RequestContext,
                            blockContext: BlockContext): Either[ExtractError, String] = Right(value)
}

case object UserIdVar extends Extractable {
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

final case class HeaderVar(header: Header.Name) extends Extractable {
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

final case class JwtPayloadVar(jsonPath: JsonPath) extends Extractable {
  override def extractUsing(requestContext: RequestContext,
                            blockContext: BlockContext): Either[ExtractError, String] = ??? //todo: impl
}
