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
package tech.beshu.ror.accesscontrol.utils

import cats.data.NonEmptyList
import cats.implicits.*
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}

object RuntimeMultiResolvableVariableOps {

  def resolveAll[T](variables: NonEmptyList[RuntimeMultiResolvableVariable[T]],
                    blockContext: BlockContext): List[T] = {
    variables
      .toList
      .flatMap { variable =>
        variable.resolve(blockContext) match {
          case Right(values) => values.toList
          case Left(_) => Nil
        }
      }
  }

  /**
   * Returns the fully resolved values when every variable is a static constant
   * (i.e. does not depend on the request/block context). In that case the result
   * is identical for every request, so structures derived from it (e.g. a
   * `PatternsMatcher`) can be built once up front instead of on every request.
   * Returns `None` when at least one value must be resolved per request.
   */
  def staticallyResolvedValues[T](variables: NonEmptyList[RuntimeMultiResolvableVariable[T]]): Option[List[T]] =
    variables
      .toList
      .traverse {
        case AlreadyResolved(values) => Some(values.toList)
        case ToBeResolved(_) => None
      }
      .map(_.flatten)
}
