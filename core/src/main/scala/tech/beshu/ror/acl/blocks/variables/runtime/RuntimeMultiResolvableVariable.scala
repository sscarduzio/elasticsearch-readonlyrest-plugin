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
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariable.ConvertError

trait RuntimeMultiResolvableVariable[T] extends RuntimeResolvableVariable[NonEmptyList[T]]
object RuntimeMultiResolvableVariable {
  final case class AlreadyResolved[T](value: NonEmptyList[T])
    extends RuntimeResolvableVariable.AlreadyResolved(value)
    with RuntimeMultiResolvableVariable[T]

  final case class ToBeResolved[T](values: NonEmptyList[MultiExtractable],
                                   convert: String => Either[ConvertError, T])
    extends RuntimeResolvableVariable.ToBeResolved(
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
