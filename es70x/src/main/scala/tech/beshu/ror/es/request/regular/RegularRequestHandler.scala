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

import cats.data.NonEmptyList
import cats.implicits._
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsResponse
import org.elasticsearch.action.search.{MultiSearchRequest, SearchRequest}
import org.elasticsearch.action.support.ActionFilterChain
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.common.collect.ImmutableOpenMap
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.tasks.Task
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult
import tech.beshu.ror.accesscontrol.AccessControlActionHandler.{ForbiddenBlockMatch, ForbiddenCause}
import tech.beshu.ror.accesscontrol.BlockContextRawDataHelper.indicesFrom
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.domain.UriPath.{CatIndicesPath, CatTemplatePath, TemplatePath}
import tech.beshu.ror.accesscontrol.request.RequestContext
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
                            threadPool: ThreadPool)
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
    // todo:
//    val searchListener = createSearchListener(requestContext, blockContext, engine.context)
//
//    val result = for {
//      _ <- requestInfo.writeResponseHeaders(BlockContextJavaHelper.responseHeadersFrom(blockContext))
//      _ <- requestInfo.writeToThreadContextHeaders(BlockContextJavaHelper.contextHeadersFrom(blockContext))
//      _ <- requestInfo.writeIndices(BlockContextJavaHelper.indicesFrom(blockContext))
//      _ <- requestInfo.writeSnapshots(BlockContextJavaHelper.snapshotsFrom(blockContext))
//      _ <- requestInfo.writeRepositories(BlockContextJavaHelper.repositoriesFrom(blockContext))
//    } yield ()
//
//    result match {
//      case WriteResult.Success(_) => proceed(searchListener)
//      case WriteResult.Failure => onForbidden(NonEmptyList.one(OperationNotAllowed))
//    }
    requestContext.uriPath match {
      case CatIndicesPath(_) if emptySetOfFoundIndices(blockContext) =>
        baseListener.onResponse(new GetSettingsResponse(
          ImmutableOpenMap.of[String, Settings](),
          ImmutableOpenMap.of[String, Settings]()
        ))
      case CatTemplatePath(_) | TemplatePath(_) =>
        val searchListener = createSearchListener(requestContext, blockContext, engine.context)
        indicesFrom(blockContext).map {
          requestInfo.writeTemplatesOf
        }
        writeCommonParts(requestInfo, blockContext)
        proceed(searchListener)
      case _ =>
        val searchListener = createSearchListener(requestContext, blockContext, engine.context)
        indicesFrom(blockContext).map {
          requestInfo.writeIndices
        }
        requestInfo.writeSnapshots(BlockContextRawDataHelper.snapshotsFrom(blockContext))
        requestInfo.writeRepositories(BlockContextRawDataHelper.repositoriesFrom(blockContext))
        writeCommonParts(requestInfo, blockContext)

        proceed(searchListener)
    }
  }

  private def writeCommonParts(requestInfo: RequestInfo, blockContext: BlockContext): Unit = {
    requestInfo.writeResponseHeaders(BlockContextRawDataHelper.responseHeadersFrom(blockContext))
    requestInfo.writeToThreadContextHeaders(BlockContextRawDataHelper.contextHeadersFrom(blockContext))
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
