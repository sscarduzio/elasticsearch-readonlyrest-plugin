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
package tech.beshu.ror.acl

import cats.data.{NonEmptyList, NonEmptySet, WriterT}
import cats.implicits._
import monix.eval.{Coeval, Task}
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.AclHandlingResult.Result
import tech.beshu.ror.acl.AclHandlingResult.Result.Success
import tech.beshu.ror.acl.domain.Header.Name
import tech.beshu.ror.acl.domain.{Header, IndexName}
import tech.beshu.ror.acl.blocks.Block.ExecutionResult.{Matched, Unmatched}
import tech.beshu.ror.acl.blocks.Block.Policy.{Allow, Forbid}
import tech.beshu.ror.acl.blocks.Block.{ExecutionResult, History}
import tech.beshu.ror.acl.blocks.{Block, BlockContext}
import tech.beshu.ror.acl.headerValues._
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.request.RequestContext

import scala.collection.SortedSet
import scala.util.Try

class SequentialAcl(val blocks: NonEmptyList[Block])
  extends Acl with Logging {

  override def handle(context: RequestContext,
                      handler: AclActionHandler): Task[AclHandlingResult] = {
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
      .map {
        case res@Matched(block, blockContext) =>
          def commitMatchChanges = block.policy match {
            case Allow => Coeval(commitChanges(blockContext, handler.onAllow(blockContext)))
            case Forbid => Coeval(handler.onForbidden())
          }
          (Success(res), commitMatchChanges)
        case res@Unmatched =>
          (Success(res), Coeval(handler.onForbidden()))
      }
      .run
      .map { case (history, (res, action)) =>
        aclResult(history, res, action)
      }
      .onErrorHandle { ex =>
        if(handler.isNotFound(ex))
          aclResult(Vector.empty, Result.NotFound(ex), Coeval(handler.onNotFound(ex)))
        else
          aclResult(Vector.empty, Result.AclError(ex), Coeval(handler.onError(ex)))
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
    writer.commit()
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
    if (blockContext.availableGroups.isEmpty) None
    else Some(Header(Name.availableGroups, blockContext.availableGroups))
  }

  private def aclResult(aHistory: Vector[History], aResult: Result, commitAction: Coeval[Unit]): AclHandlingResult =
    new AclHandlingResult {
      override def commit(): Try[Unit] = {
        commitAction.runAttempt().toTry
      }
      override val history: Vector[History] = aHistory
      override val handlingResult: Result = aResult
    }
}
