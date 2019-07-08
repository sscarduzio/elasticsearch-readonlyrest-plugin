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
import cats.instances.either._
import cats.syntax.order._
import cats.syntax.traverse._
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.acl.request.RequestContext

sealed trait RuntimeSingleResolvableVariable[T] extends RuntimeResolvableVariable[T]

object RuntimeSingleResolvableVariable {

  final case class AlreadyResolved[T](value: T)
    extends RuntimeSingleResolvableVariable[T] {

    override def resolve(requestContext: RequestContext,
                         blockContext: BlockContext): Either[RuntimeResolvableVariable.Unresolvable, T] =
      Right(value)
  }

  final case class ToBeResolved[T : Convertible](values: NonEmptyList[SingleExtractable])
    extends RuntimeSingleResolvableVariable[T] {

    override def resolve(requestContext: RequestContext,
                         blockContext: BlockContext): Either[RuntimeResolvableVariable.Unresolvable, T] = {
      values
        .map { extractable =>
          extractable
            .extractUsing(requestContext, blockContext)
            .left.map(error => RuntimeResolvableVariable.Unresolvable.CannotExtractValue(error.msg))
        }
        .sequence
        .map(_.toList.mkString)
        .flatMap { result =>
          implicitly[Convertible[T]].convert(result)
            .left.map(error => RuntimeResolvableVariable.Unresolvable.CannotInstantiateResolvedValue(error.msg))
        }
    }
  }

  implicit def runtimeResolvableOrder[T: Order]: Order[RuntimeSingleResolvableVariable[T]] =
    Order.from {
      case (AlreadyResolved(c1), AlreadyResolved(c2)) => c1 compare c2
      case (AlreadyResolved(_), _) => -1
      case (_, AlreadyResolved(_)) => 1
      case (v1, v2) => v1.hashCode() compareTo v2.hashCode()
    }

}
