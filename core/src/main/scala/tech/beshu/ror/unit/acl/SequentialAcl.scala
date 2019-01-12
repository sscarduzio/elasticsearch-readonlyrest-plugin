package tech.beshu.ror.unit.acl

import cats.data.{NonEmptyList, WriterT}
import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.unit.acl.blocks.Block.ExecutionResult.{Matched, Unmatched}
import tech.beshu.ror.unit.acl.blocks.Block.Policy.{Allow, Forbid}
import tech.beshu.ror.unit.acl.blocks.Block.{ExecutionResult, History}
import tech.beshu.ror.unit.acl.blocks.{Block, BlockContext}
import tech.beshu.ror.unit.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.aDomain.Header.Name
import tech.beshu.ror.commons.headerValues._

class SequentialAcl(val blocks: NonEmptyList[Block])
  extends Acl {

  override def handle(context: RequestContext, handler: AclHandler): Task[(Vector[History], ExecutionResult)] = {
    blocks
      .tail
      .foldLeft(checkBlock(blocks.head, context)) { case (currentResult, block) =>
        for {
          prevBlocksExecutionResult <- currentResult
          newCurrentResult <- prevBlocksExecutionResult match {
            case Unmatched =>
              checkBlock(block, context)
            case Matched(_, _) =>
              lift(prevBlocksExecutionResult)
          }
        } yield newCurrentResult
      }
      .mapBoth {
        case res@(_, Matched(block, blockContext)) =>
          block.policy match {
            case Allow => commitChanges(blockContext, handler.onAllow(blockContext))
            case Forbid => handler.onForbidden()
          }
          res
        case res@(_, Unmatched) =>
          handler.onForbidden()
          res
      }
      .run
      .onErrorHandleWith { ex =>
        if (handler.isNotFound(ex)) handler.onNotFound(ex)
        else handler.onError(ex)
        Task.raiseError(ex)
      }
  }

  private def checkBlock(block: Block, requestContent: RequestContext): WriterT[Task, Vector[History], ExecutionResult] = {
    WriterT.apply {
      block
        .execute(requestContent)
        .map { case (r, history) => (Vector(history), r) }
    }
  }

  private def lift(executionResult: ExecutionResult): WriterT[Task, Vector[History], ExecutionResult] = {
    WriterT.value[Task, Vector[History], ExecutionResult](executionResult)
  }

  private def commitChanges(blockContext: BlockContext, writer: ResponseWriter): Unit = {
    commitResponseHeaders(blockContext, writer)
    commitContextHeaders(blockContext, writer)
  }

  private def commitResponseHeaders(blockContext: BlockContext, writer: ResponseWriter): Unit = {
    val responseHeaders =
      userRelatedHeadersFrom(blockContext) ++ blockContext.responseHeaders ++ kibanaIndexHeaderFrom(blockContext).toSet
    if (responseHeaders.nonEmpty) writer.writeResponseHeaders(responseHeaders.map(h => (h.name.value, h.value)).toMap)
  }

  private def commitContextHeaders(blockContext: BlockContext, writer: ResponseWriter): Unit = {
    blockContext.contextHeaders.foreach { h =>
      writer.writeToThreadContextHeader(h.name.value, h.value)
    }
  }

  private def userRelatedHeadersFrom(blockContext: BlockContext): Set[Header] = {
    blockContext.loggedUser match {
      case Some(user) =>
        // todo: groups
        Set(Header(Name.rorUser, user.id))
      case None =>
        Set.empty
    }
  }

  private def kibanaIndexHeaderFrom(blockContext: BlockContext): Option[Header] = {
    blockContext.kibanaIndex.map(i => Header(Name.kibanaIndex, i))
  }

  override val involvesFilter: Boolean = false // todo: impl

  override val doesRequirePassword: Boolean = false // todo: impl
}
