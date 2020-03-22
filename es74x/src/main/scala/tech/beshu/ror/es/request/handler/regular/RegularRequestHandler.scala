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
package tech.beshu.ror.es.request.handler.regular

import cats.data.NonEmptyList
import monix.eval.Task
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsResponse
import org.elasticsearch.action.support.ActionFilterChain
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.common.collect.ImmutableOpenMap
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.tasks.{Task => EsTask}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult.ForbiddenByMismatched.Cause
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.Outcome
import tech.beshu.ror.accesscontrol.domain.UriPath.CatIndicesPath
import tech.beshu.ror.accesscontrol.domain.{IndexName, Operation}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.boot.Engine
import tech.beshu.ror.es.request.ForbiddenResponse
import tech.beshu.ror.es.request.context.EsRequest
import tech.beshu.ror.es.request.handler.RequestHandler
import tech.beshu.ror.es.request.handler.regular.RegularRequestHandler.{ForbiddenBlockMatch, ForbiddenCause, OperationNotAllowed, fromMismatchedCause}
import tech.beshu.ror.utils.LoggerOps._
import tech.beshu.ror.utils.ScalaOps._

import scala.util.{Failure, Success, Try}

class RegularRequestHandler[B <: BlockContext.Aux[B, O], O <: Operation](engine: Engine,
                                                                         task: EsTask,
                                                                         action: String,
                                                                         request: ActionRequest,
                                                                         baseListener: ActionListener[ActionResponse],
                                                                         chain: ActionFilterChain[ActionRequest, ActionResponse],
                                                                         channel: RestChannel,
                                                                         threadPool: ThreadPool)
                                                                        (implicit scheduler: Scheduler)
  extends RequestHandler[B, O] with Logging {

  override def handle(request: RequestContext.Aux[O, B] with EsRequest[B]): Task[Unit] = {
    engine.accessControl
      .handleRegularRequest(request)
      .map { r =>
        threadPool.getThreadContext.stashContext.bracket { _ =>
          commitResult(r.result, request)
        }
      }
  }

  private def commitResult(result: RegularRequestResult[B],
                           request: EsRequest[B] with RequestContext.Aux[O, B]): Unit = {
    Try {
      result match {
        case allow: RegularRequestResult.Allow[B] =>
          onAllow(request, allow.blockContext)
        case RegularRequestResult.ForbiddenBy(_, _) =>
          onForbidden(NonEmptyList.one(ForbiddenBlockMatch))
        case RegularRequestResult.ForbiddenByMismatched(causes) =>
          onForbidden(causes.toNonEmptyList.map(fromMismatchedCause))
        case RegularRequestResult.IndexNotFound() =>
          onIndexNotFound(request)
        case RegularRequestResult.Failed(ex) =>
          baseListener.onFailure(ex.asInstanceOf[Exception])
        case RegularRequestResult.PassedThrough() =>
          proceed(baseListener)
      }
    } match {
      case Success(_) =>
      case Failure(ex) =>
        logger.errorEx("ACL committing result failure", ex)
    }
  }

  private def onAllow(request: EsRequest[B] with RequestContext.Aux[O, B],
                      blockContext: B): Unit = {
    request.uriPath match {
      //        case CatIndicesPath(_) if emptySetOfFoundIndices(blockContext) =>
      //          respondWithEmptyCatIndicesResponse()
      //      case CatTemplatePath(_) | TemplatePath(_) =>
      //        proceedAfterSuccessfulWrite(requestContext, blockContext) {
      //          for {
      //            _ <- writeTemplatesIfNeeded(blockContext)
      //            _ <- writeCommonParts(blockContext)
      //          } yield ()
      //        }
      case _ =>
        request.modifyUsing(blockContext)
      //        proceedAfterSuccessfulWrite(requestContext, blockContext) {
      //          for {
      //            _ <- writeIndicesIfNeeded(blockContext)
      //            _ <- requestInfo.writeSnapshots(BlockContextRawDataHelper.snapshotsFrom(blockContext))
      //            _ <- requestInfo.writeRepositories(BlockContextRawDataHelper.repositoriesFrom(blockContext))
      //            _ <- writeCommonParts(blockContext)
      //          } yield ()
      //        }
    }
  }

  private def onForbidden(causes: NonEmptyList[ForbiddenCause]): Unit = {
    channel.sendResponse(ForbiddenResponse.create(channel, causes.toList, engine.context))
  }

  private def onIndexNotFound(request: RequestContext[O] with EsRequest[B]): Unit = {
    request.uriPath match {
      case CatIndicesPath(_) =>
        respondWithEmptyCatIndicesResponse()
      case _ if engine.context.doesRequirePassword => // this is required by free kibana users who want to see basic auth prompt
        val nonExistentIndex = randomNonexistentIndex(request)
        if (nonExistentIndex.hasWildcard) {
          proceedWithModifiedIndexIfPossible(request, nonExistentIndex)
        } else {
          onForbidden(NonEmptyList.one(OperationNotAllowed))
        }
      case _ =>
        proceedWithModifiedIndexIfPossible(request, randomNonexistentIndex(request))
    }
  }

  private def proceedWithModifiedIndexIfPossible(request: RequestContext[O] with EsRequest[B], index: IndexName): Unit = {
    //    request match {
    //      case value: NonIndexOperationRequestContext[_] =>
    //      case value: AnIndexOperationRequestContext[_] =>
    //      // todo: create artificial block context with the index, modify the request and that's it
    //    }
  }

  //
  //  private def proceedAfterSuccessfulWrite[T <: Operation](requestContext: RequestContext[T],
  //                                          blockContext: BlockContext[T])
  //                                         (result: WriteResult[Unit]): Unit = {
  //  requestContext match {
  //    case value: NonIndexOperationRequestContext[_] =>
  //    case value: AnIndexOperationRequestContext[_] =>
  //  }
  //    result match {
  //      case WriteResult.Success(_) =>
  //        val searchListener = createSearchListener(requestContext, blockContext, engine.context)
  //        proceed(searchListener)
  //      case WriteResult.Failure =>
  //        logger.error("Cannot modify incoming request. Passing it could lead to a security leak. Report this issue as fast as you can.")
  //        onForbidden(NonEmptyList.one(OperationNotAllowed))
  //    }
  //  }
  //
  //  private def writeTemplatesIfNeeded(blockContext: BlockContext) = {
  //    writeIndicesBasedResultIfNeeded(
  //      blockContext,
  //      requestInfo,
  //      requestInfo.writeTemplatesOf
  //    )
  //  }
  //
  //  private def writeIndicesIfNeeded(blockContext: BlockContext) = {
  //    writeIndicesBasedResultIfNeeded(
  //      blockContext,
  //      requestInfo,
  //      requestInfo.writeIndices
  //    )
  //  }
  //
  //  private def writeIndicesBasedResultIfNeeded(blockContext: BlockContext,
  //                                              requestInfo: RequestInfo,
  //                                              write: Set[String] => WriteResult[Unit]) = {
  //    indicesFrom(blockContext) match {
  //      case Outcome.Exist(indices) => write(indices)
  //      case Outcome.NotExist => WriteResult.Success(())
  //    }
  //  }
  //
  //  private def writeCommonParts(requestInfo: RequestInfo, blockContext: BlockContext) = {
  //    for {
  //      _ <- requestInfo.writeResponseHeaders(BlockContextRawDataHelper.responseHeadersFrom(blockContext))
  //      _ <- requestInfo.writeToThreadContextHeaders(BlockContextRawDataHelper.contextHeadersFrom(blockContext))
  //    } yield ()
  //  }
  private def emptySetOfFoundIndices(blockContext: B) = {
    blockContext.indices match {
      case Outcome.Exist(foundIndices) => foundIndices.isEmpty
      case Outcome.NotExist => false
    }
  }

  private def proceed(responseActionListener: ActionListener[ActionResponse]): Unit =
    chain.proceed(task, action, request, responseActionListener)

  //  private def createSearchListener(requestContext: RequestContext,
  //                                   blockContext: BlockContext,
  //                                   aclStaticContext: AccessControlStaticContext): ActionListener[ActionResponse] = {
  //    Try {
  //      // Cache disabling for those 2 kind of request is crucial for
  //      // document level security to work. Otherwise we'd get an answer from
  //      // the cache some times and would not be filtered
  //      if (aclStaticContext.involvesFilter) {
  //        request match {
  //          case r: SearchRequest =>
  //            logger.debug("ACL involves filters, will disable request cache for SearchRequest")
  //            r.requestCache(false)
  //          case r: MultiSearchRequest =>
  //            logger.debug("ACL involves filters, will disable request cache for MultiSearchRequest")
  //            r.requests().asScala.foreach(_.requestCache(false))
  //          case _ =>
  //        }
  //      }
  //      new RegularResponseActionListener(baseListener.asInstanceOf[ActionListener[ActionResponse]], requestContext, blockContext)
  //    } fold(
  //      e => {
  //        logger.error("on allow exception", e)
  //        baseListener
  //      },
  //      identity
  //    )
  //  }
  //
  private def randomNonexistentIndex(requestContext: RequestContext[O]): IndexName = {
    // todo:
    requestContext.__old_indices.headOption match {
      case Some(indexName) => IndexName.randomNonexistentIndex(indexName.value.value)
      case None => IndexName.randomNonexistentIndex()
    }
  }

  private def respondWithEmptyCatIndicesResponse(): Unit = {
    baseListener.onResponse(new GetSettingsResponse(
      ImmutableOpenMap.of[String, Settings](),
      ImmutableOpenMap.of[String, Settings]()
    ))
  }
}

object RegularRequestHandler {
  sealed trait ForbiddenCause {
    def stringify: String
  }
  case object ForbiddenBlockMatch extends ForbiddenCause {
    override def stringify: String = "FORBIDDEN_BY_BLOCK"
  }
  case object OperationNotAllowed extends ForbiddenCause {
    override def stringify: String = "OPERATION_NOT_ALLOWED"
  }
  case object ImpersonationNotSupported extends ForbiddenCause {
    override def stringify: String = "IMPERSONATION_NOT_SUPPORTED"
  }
  case object ImpersonationNotAllowed extends ForbiddenCause {
    override def stringify: String = "IMPERSONATION_NOT_ALLOWED"
  }

  def fromMismatchedCause(cause: Cause): ForbiddenCause = {
    cause match {
      case Cause.OperationNotAllowed => OperationNotAllowed
      case Cause.ImpersonationNotSupported => ImpersonationNotSupported
      case Cause.ImpersonationNotAllowed => ImpersonationNotAllowed
    }
  }
}