package tech.beshu.ror.acl

import cats.implicits._
import cats.data.NonEmptyList
import monix.eval.Task
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.blocks.Block.ExecutionResult.{Matched, Mismatched}
import tech.beshu.ror.acl.blocks.Block.Policy
import tech.beshu.ror.acl.request.RequestContext

class ParallelAcl(val blocks: NonEmptyList[Block])
  extends Acl {

  override def handle(context: RequestContext): Task[AclHandlingResult] = {
    Task
      .gather(blocks.toList.map(blockExecute(_, context)))
      .map(_.flatten)
      .map { blockResults =>
        blockResults.map {
          case (Matched(block, blockContext), _) if block.policy === Policy.Allow =>
          case (Matched(block, blockContext), _) =>
          case (Mismatched(blockContext), _) =>

        }
      }
    ???
  }

  private def blockExecute(block: Block, context: RequestContext) = {
    block
      .execute(context)
      .map(Some.apply)
      .onErrorRecover { case _ => None }
  }
}
