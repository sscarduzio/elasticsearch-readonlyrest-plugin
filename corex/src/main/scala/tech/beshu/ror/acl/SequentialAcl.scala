package tech.beshu.ror.acl

import cats.data.{NonEmptyList, NonEmptySet, WriterT}
import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.blocks.{Block, BlockContext}
import tech.beshu.ror.acl.blocks.Block.ExecutionResult.{Matched, Unmatched}
import tech.beshu.ror.acl.blocks.Block.Policy.{Allow, Forbid}
import tech.beshu.ror.acl.blocks.Block.{ExecutionResult, History}
import tech.beshu.ror.acl.request.RequestContext
import aDomain.{Header, IndexName}
import aDomain.Header.Name
import headerValues._
import org.apache.logging.log4j.scala.Logging

import scala.collection.SortedSet

class SequentialAcl(val blocks: NonEmptyList[Block])
  extends Acl {

  override def handle(context: RequestContext,
                      handler: AclHandler): Task[(Vector[History], ExecutionResult)] = {
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
    commitIndices(blockContext, writer)
    commitSnapshots(blockContext, writer)
    commitRepositories(blockContext, writer)
  }

  private def commitResponseHeaders(blockContext: BlockContext, writer: ResponseWriter): Unit = {
    val responseHeaders =
      blockContext.responseHeaders ++ userRelatedHeadersFrom(blockContext) ++
        kibanaIndexHeaderFrom(blockContext).toSet ++ currentGroupHeaderFrom(blockContext).toSet ++
        availableGroupsHeaderFrom(blockContext).toSet
    if (responseHeaders.nonEmpty) writer.writeResponseHeaders(responseHeaders.map(h => (h.name.value.value, h.value.value)).toMap)
  }

  private def commitContextHeaders(blockContext: BlockContext, writer: ResponseWriter): Unit = {
    blockContext.contextHeaders.foreach { h =>
      writer.writeToThreadContextHeader(h.name.value.value, h.value.value)
    }
  }

  private def commitIndices(blockContext: BlockContext, writer: ResponseWriter): Unit = {
    NonEmptySet.fromSet(SortedSet.empty[IndexName] ++ blockContext.indices) match {
      case Some(indices) => writer.writeIndices(indices.toSortedSet.map(_.value))
      case None =>
    }
  }

  private def commitRepositories(blockContext: BlockContext, writer: ResponseWriter): Unit = {
    NonEmptySet.fromSet(SortedSet.empty[IndexName] ++ blockContext.repositories) match {
      case Some(indices) => writer.writeRepositories(indices.toSortedSet.map(_.value))
      case None =>
    }
  }

  private def commitSnapshots(blockContext: BlockContext, writer: ResponseWriter): Unit = {
    NonEmptySet.fromSet(SortedSet.empty[IndexName] ++ blockContext.snapshots) match {
      case Some(indices) => writer.writeSnapshots(indices.toSortedSet.map(_.value))
      case None =>
    }
  }

  private def userRelatedHeadersFrom(blockContext: BlockContext): Option[Header] = {
    blockContext.loggedUser.map(user => Header(Name.rorUser, user.id))
  }

  private def kibanaIndexHeaderFrom(blockContext: BlockContext): Option[Header] = {
    blockContext.kibanaIndex.map(i => Header(Name.kibanaIndex, i))
  }

  private def currentGroupHeaderFrom(blockContext: BlockContext): Option[Header] = {
    blockContext.currentGroup.map(r => Header(Name.currentGroup, r))
  }

  private def availableGroupsHeaderFrom(blockContext: BlockContext): Option[Header] = {
    if(blockContext.availableGroups.isEmpty) None
    else Some(Header(Name.availableGroups, blockContext.availableGroups))
  }
}
