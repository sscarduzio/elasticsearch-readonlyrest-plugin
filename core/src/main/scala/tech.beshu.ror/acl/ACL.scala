package tech.beshu.ror.acl

import cats.data.NonEmptyList
import cats.implicits._
import com.typesafe.scalalogging.StrictLogging
import monix.eval.Task
import tech.beshu.ror.acl.blocks.{Block, BlockContext}
import tech.beshu.ror.acl.blocks.Block.ExecutionResult
import tech.beshu.ror.acl.blocks.Block.ExecutionResult.{Matched, Unmatched}
import tech.beshu.ror.acl.request.EsRequestContext
import tech.beshu.ror.acl.request.RequestContext.Id._
import tech.beshu.ror.acl.utils.TaskOps._
import tech.beshu.ror.commons.shims.es.ACLHandler
import tech.beshu.ror.commons.shims.request.RequestInfoShim

import scala.util.Success

class ACL(blocks: NonEmptyList[Block])
  extends StrictLogging {

  // todo: history
  def check(rInfo: RequestInfoShim, handler: ACLHandler): Task[Unit] = {
    val context = new EsRequestContext(rInfo)
    logger.debug(s"checking request: ${context.id.show}")
    blocks
      .foldLeft(unmatched) {
        case (acc, block) => acc.flatMap {
          case Unmatched =>
            for {
              result <- block
                .execute(context)
                .andThen {
                  case Success((Matched(blockContext), _)) if block.policy === Block.Policy.Allow =>
                    commitChanges(blockContext, rInfo)
                }
                .map(_._1)
            } yield result
          case matched =>
            Task.now(matched)
        }
      }
      .map(_ => {})
  }

  private def commitChanges(blockContext: BlockContext, rInfo: RequestInfoShim): Unit = ???

  private val unmatched = Task.now(Block.ExecutionResult.Unmatched: ExecutionResult)

}
