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
package tech.beshu.ror.es.request.regular

import cats.implicits._
import cats.data.NonEmptyList
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse
import org.elasticsearch.action.search.{MultiSearchRequest, SearchRequest}
import org.elasticsearch.action.support.ActionFilterChain
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.tasks.Task
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult
import tech.beshu.ror.accesscontrol.AccessControlActionHandler.{ForbiddenBlockMatch, ForbiddenCause, OperationNotAllowed}
import tech.beshu.ror.accesscontrol.BlockContextRawDataHelper.indicesFrom
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.Outcome
import tech.beshu.ror.accesscontrol.domain.UriPath.{CatIndicesPath, CatTemplatePath, TemplatePath}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestInfoShim.{ExtractedIndices, WriteResult}
import tech.beshu.ror.accesscontrol.{AccessControlActionHandler, AccessControlStaticContext, BlockContextRawDataHelper}
import tech.beshu.ror.boot.Engine
import tech.beshu.ror.es.request.{ForbiddenResponse, RequestInfo}
import tech.beshu.ror.utils.LoggerOps._
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

class RegularRequestHandler(engine: Engine,
                            task: Task,
                            action: String,
                            request: ActionRequest,
                            baseListener: ActionListener[ActionResponse],
                            chain: ActionFilterChain[ActionRequest, ActionResponse],
                            channel: RestChannel,
                            threadPool: ThreadPool,
                            emptyClusterStateResponse: ClusterStateResponse)
                           (implicit scheduler: Scheduler)
  extends Logging {

  def handle(requestInfo: RequestInfo, requestContext: RequestContext): Unit = {
    engine.accessControl
      .handleRegularRequest(requestContext)
      .runAsync {
        case Right(r) =>
          threadPool.getThreadContext.stashContext.bracket { _ =>
            commitResult(r.result, requestContext, requestInfo)
          }
        case Left(ex) =>
          baseListener.onFailure(new Exception(ex))
      }
  }

  private def commitResult(result: RegularRequestResult,
                           requestContext: RequestContext,
                           requestInfo: RequestInfo): Unit = {
    Try {
      result match {
        case RegularRequestResult.Allow(blockContext, _) =>
          onAllow(requestContext, requestInfo, blockContext)
        case RegularRequestResult.ForbiddenBy(_, _) =>
          onForbidden(NonEmptyList.one(ForbiddenBlockMatch))
        case RegularRequestResult.ForbiddenByMismatched(causes) =>
          onForbidden(causes.toNonEmptyList.map(AccessControlActionHandler.fromMismatchedCause))
        case RegularRequestResult.Failed(ex) =>
          baseListener.onFailure(ex.asInstanceOf[Exception])
        case RegularRequestResult.PassedThrough =>
          proceed(baseListener)
      }
    } match {
      case Success(_) =>
      case Failure(ex) =>
        logger.errorEx("ACL committing result failure", ex)
    }
  }

  private def onAllow(requestContext: RequestContext,
                      requestInfo: RequestInfo,
                      blockContext: BlockContext): Unit = {
    requestContext.uriPath match {
      case CatIndicesPath(_) if emptySetOfFoundIndices(blockContext) =>
        baseListener.onResponse(emptyClusterStateResponse)
      case CatTemplatePath(_) | TemplatePath(_) =>
        proceedAfterSuccessfulWrite(requestContext, blockContext) {
          for {
            _ <- writeTemplatesIfNeeded(blockContext, requestInfo)
            _ <- writeCommonParts(requestInfo, blockContext)
          } yield ()
        }
      case _ =>
        proceedAfterSuccessfulWrite(requestContext, blockContext) {
          for {
            _ <- writeIndicesIfNeeded(blockContext, requestInfo)
            _ <- requestInfo.writeSnapshots(BlockContextRawDataHelper.snapshotsFrom(blockContext))
            _ <- requestInfo.writeRepositories(BlockContextRawDataHelper.repositoriesFrom(blockContext))
            _ <- writeCommonParts(requestInfo, blockContext)
          } yield ()
        }
    }
  }

  private def proceedAfterSuccessfulWrite(requestContext: RequestContext,
                                          blockContext: BlockContext)
                                         (result: WriteResult[Unit]): Unit = {
    result match {
      case WriteResult.Success(_) =>
        val searchListener = createSearchListener(requestContext, blockContext, engine.context)
        proceed(searchListener)
      case WriteResult.Failure =>
        logger.error("Cannot modify incoming request. Passing it could lead to a security leak. Report this issue as fast as you can.")
        onForbidden(NonEmptyList.one(OperationNotAllowed))
    }
  }

  private def writeTemplatesIfNeeded(blockContext: BlockContext, requestInfo: RequestInfo) = {
    writeIndicesBasedResultIfNeeded(
      blockContext,
      requestInfo,
      requestInfo.writeTemplatesOf
    )
  }

  private def writeIndicesIfNeeded(blockContext: BlockContext, requestInfo: RequestInfo) = {
    writeIndicesBasedResultIfNeeded(
      blockContext,
      requestInfo,
      requestInfo.writeIndices
    )
  }

  private def writeIndicesBasedResultIfNeeded(blockContext: BlockContext,
                                              requestInfo: RequestInfo,
                                              write: Set[String] => WriteResult[Unit]) = {
    requestInfo.extractIndices match {
      case ExtractedIndices.NoIndices => WriteResult.Success(())
      case ExtractedIndices.RegularIndices(_) | _: ExtractedIndices.SqlIndices =>
        indicesFrom(blockContext) match {
          case Outcome.Exist(indices) => write(indices)
          case Outcome.NotExist => WriteResult.Success(())
        }
    }
  }

  private def writeCommonParts(requestInfo: RequestInfo, blockContext: BlockContext) = {
    for {
      _ <- requestInfo.writeResponseHeaders(BlockContextRawDataHelper.responseHeadersFrom(blockContext))
      _ <- requestInfo.writeToThreadContextHeaders(BlockContextRawDataHelper.contextHeadersFrom(blockContext))
    } yield ()
  }

  private def emptySetOfFoundIndices(blockContext: BlockContext) = {
    blockContext.indices.forall(_.isEmpty)
  }

  private def onForbidden(causes: NonEmptyList[ForbiddenCause]): Unit = {
    channel.sendResponse(ForbiddenResponse.create(channel, causes.toList, engine.context))
  }

  private def proceed(responseActionListener: ActionListener[ActionResponse]): Unit =
    chain.proceed(task, action, request, responseActionListener)

  private def createSearchListener(requestContext: RequestContext,
                                   blockContext: BlockContext,
                                   aclStaticContext: AccessControlStaticContext): ActionListener[ActionResponse] = {
    Try {
      // Cache disabling for those 2 kind of request is crucial for
      // document level security to work. Otherwise we'd get an answer from
      // the cache some times and would not be filtered
      if (aclStaticContext.involvesFilter) {
        request match {
          case r: SearchRequest =>
            logger.debug("ACL involves filters, will disable request cache for SearchRequest")
            r.requestCache(false)
          case r: MultiSearchRequest =>
            logger.debug("ACL involves filters, will disable request cache for MultiSearchRequest")
            r.requests().asScala.foreach(_.requestCache(false))
          case _ =>
        }
      }
      new RegularResponseActionListener(baseListener.asInstanceOf[ActionListener[ActionResponse]], requestContext, blockContext)
    } fold(
      e => {
        logger.error("on allow exception", e)
        baseListener
      },
      identity
    )
  }

}
