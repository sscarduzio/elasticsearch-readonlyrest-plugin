package tech.beshu.ror.acl.utils

import cats.data.NonEmptySet
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.acl.request.RequestContext

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
