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

import cats.data.NonEmptySet
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.request.RequestContext

object RuntimeMultiResolvableVariableOps {

  def resolveAll[T](variables: NonEmptySet[RuntimeMultiResolvableVariable[T]],
                    requestContext: RequestContext,
                    blockContext: BlockContext): List[T] = {
    variables
      .toNonEmptyList
      .toList
      .flatMap { variable =>
        variable.resolve(requestContext, blockContext) match {
          case Right(values) => values.toList
          case Left(_) => Nil
        }
      }
  }
}
