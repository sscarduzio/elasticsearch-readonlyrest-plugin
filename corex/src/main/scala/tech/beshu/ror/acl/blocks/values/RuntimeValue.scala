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
package tech.beshu.ror.acl.blocks.values

import cats.Order
import cats.implicits._
import com.jayway.jsonpath.JsonPath
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.values.RuntimeValue.ExtractError
import tech.beshu.ror.acl.blocks.values.Variable.Unresolvable.{CannotExtractValue, CannotInstantiateResolvedValue}
import tech.beshu.ror.acl.blocks.values.Variable.{ConvertError, Unresolvable}
import tech.beshu.ror.acl.domain.Header
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.request.RequestContext

sealed trait RuntimeValue {
  def extract(requestContext: RequestContext,
              blockContext: BlockContext): Either[ExtractError, String]
}

object RuntimeValue {

  final case class ExtractError(msg: String) extends AnyVal
}

final case class Const(value: String) extends RuntimeValue {
  override def extract(requestContext: RequestContext,
                       blockContext: BlockContext): Either[ExtractError, String] = Right(value)
}

case object UserIdVar extends RuntimeValue {
  override def extract(requestContext: RequestContext,
                       blockContext: BlockContext): Either[ExtractError, String] = {
    blockContext
      .loggedUser
      .map(_.id.value) match {
      case Some(value) => Right(value)
      case None => Left(ExtractError("Cannot extract user ID from block context"))
    }
  }
}

final case class HeaderVar(header: Header.Name) extends RuntimeValue {
  override def extract(requestContext: RequestContext,
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

final case class JwtPayloadVar(jsonPath: JsonPath) extends RuntimeValue {
  override def extract(requestContext: RequestContext,
                       blockContext: BlockContext): Either[ExtractError, String] = ???
}

sealed trait Variable[T] {

  def resolve(requestContext: RequestContext,
              blockContext: BlockContext): Either[Unresolvable, T]
}

object Variable {

  final case class ConvertError(value: String, msg: String)

  sealed trait Unresolvable
  object Unresolvable {
    final case class CannotExtractValue(msg: String) extends Unresolvable
    final case class CannotInstantiateResolvedValue(msg: String) extends Unresolvable
  }

  implicit def variableOrder[T : Order]: Order[Variable[T]] = Order.by(_.hashCode())
}

final case class AlreadyResolved[T](value: T)
  extends Variable[T] {

  override def resolve(requestContext: RequestContext,
                       blockContext: BlockContext): Either[Unresolvable, T] =
    Right(value)
}

final case class ToBeResolved[T](values: List[RuntimeValue],
                                 convert: String => Either[ConvertError, T])
  extends Variable[T] {

  override def resolve(requestContext: RequestContext,
                       blockContext: BlockContext): Either[Unresolvable, T] = {
    values
      .foldLeft(Either.right[Unresolvable, String]("")) {
        case (Right(accumulator), value) =>
          value
            .extract(requestContext, blockContext)
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