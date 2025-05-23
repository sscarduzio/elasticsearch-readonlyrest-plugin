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
package tech.beshu.ror.es.handler

import cats.data.NonEmptyList
import cats.implicits.*
import monix.eval.Task
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.{ActionListener, ActionResponse}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlList.RegularRequestResult
import tech.beshu.ror.accesscontrol.blocks.BlockContext.*
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.*
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, FilteredResponseFields, ResponseTransformation}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.response.ForbiddenResponseContext
import tech.beshu.ror.accesscontrol.response.ForbiddenResponseContext.Cause.fromMismatchedCause
import tech.beshu.ror.accesscontrol.response.ForbiddenResponseContext.{ForbiddenBlockMatch, OperationNotAllowed}
import tech.beshu.ror.boot.ReadonlyRest.Engine
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult.{CustomResponse, UpdateResponse}
import tech.beshu.ror.es.handler.request.context.{EsRequest, ModificationResult}
import tech.beshu.ror.es.handler.response.ForbiddenResponse
import tech.beshu.ror.es.utils.ThreadContextOps.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.utils.LoggerOps.*
import tech.beshu.ror.utils.ScalaOps.*

import java.time.{Duration, Instant}
import scala.util.{Failure, Success, Try}

class RegularRequestHandler(engine: Engine,
                            esContext: EsContext,
                            threadPool: ThreadPool)
                           (implicit scheduler: Scheduler)
  extends Logging {

  def handle[B <: BlockContext : BlockContextUpdater](request: RequestContext.Aux[B] with EsRequest[B]): Task[Unit] = {
    engine.core.accessControl
      .handleRegularRequest(request)
      .map { r =>
        threadPool.getThreadContext.stashAndMergeResponseHeaders(esContext).bracket { _ =>
          commitResult(r.result, request)
        }
      }
  }

  private def commitResult[B <: BlockContext : BlockContextUpdater](result: RegularRequestResult[B],
                                                                    request: EsRequest[B] with RequestContext.Aux[B]): Unit = {
    Try {
      result match {
        case allow: RegularRequestResult.Allow[B] =>
          onAllow(request, allow.blockContext)
        case RegularRequestResult.ForbiddenBy(_, block) =>
          onForbidden(request, NonEmptyList.one(ForbiddenBlockMatch(block)))
        case RegularRequestResult.ForbiddenByMismatched(causes) =>
          onForbidden(request, causes.toNonEmptyList.map(fromMismatchedCause))
        case RegularRequestResult.IndexNotFound() =>
          onIndexNotFound(request)
        case RegularRequestResult.AliasNotFound() =>
          onAliasNotFound(request)
        case RegularRequestResult.TemplateNotFound() =>
          onTemplateNotFound(request)
        case RegularRequestResult.Failed(ex) =>
          esContext.listener.onFailure(ex.asInstanceOf[Exception])
        case RegularRequestResult.PassedThrough() =>
          proceed(request, esContext.listener)
      }
    } match {
      case Success(_) =>
      case Failure(ex) =>
        logger.errorEx(s"[${request.id.toRequestId.show}] ACL committing result failure", ex)
        esContext.listener.onFailure(ex.asInstanceOf[Exception])
    }
  }

  private def onAllow[B <: BlockContext](request: EsRequest[B] with RequestContext.Aux[B],
                                         blockContext: B): Unit = {
    configureResponseTransformations(blockContext.responseTransformations)
    request.modifyUsing(blockContext) match {
      case ModificationResult.Modified =>
        proceed(request)
      case ModificationResult.ShouldBeInterrupted =>
        onForbidden(request, NonEmptyList.one(OperationNotAllowed))
      case ModificationResult.CannotModify =>
        logger.error(s"[${request.id.toRequestId.show}] Cannot modify incoming request. Passing it could lead to a security leak. Report this issue as fast as you can.")
        onForbidden(request, NonEmptyList.one(OperationNotAllowed))
      case CustomResponse(response) =>
        respond(request, response)
      case UpdateResponse(updateFunc) =>
        proceed(request, new UpdateResponseListener(updateFunc))
    }
  }

  private def onForbidden(requestContext: RequestContext, causes: NonEmptyList[ForbiddenResponseContext.Cause]): Unit = {
    logRequestProcessingTime(requestContext)
    esContext.listener.onFailure(ForbiddenResponse.create(
      ForbiddenResponseContext.from(causes, engine.core.accessControl.staticContext)
    ))
  }

  private def onIndexNotFound[B <: BlockContext : BlockContextUpdater](request: EsRequest[B] with RequestContext.Aux[B]): Unit = {
    BlockContextUpdater[B] match {
      case GeneralIndexRequestBlockContextUpdater =>
        handleIndexNotFoundForGeneralIndexRequest(request.asInstanceOf[EsRequest[GeneralIndexRequestBlockContext] with RequestContext.Aux[GeneralIndexRequestBlockContext]])
      case FilterableRequestBlockContextUpdater =>
        handleIndexNotFoundForSearchRequest(request.asInstanceOf[EsRequest[FilterableRequestBlockContext] with RequestContext.Aux[FilterableRequestBlockContext]])
      case FilterableMultiRequestBlockContextUpdater =>
        handleIndexNotFoundForMultiSearchRequest(request.asInstanceOf[EsRequest[FilterableMultiRequestBlockContext] with RequestContext.Aux[FilterableMultiRequestBlockContext]])
      case AliasRequestBlockContextUpdater =>
        handleIndexNotFoundForAliasRequest(request.asInstanceOf[EsRequest[AliasRequestBlockContext] with RequestContext.Aux[AliasRequestBlockContext]])
      case CurrentUserMetadataRequestBlockContextUpdater |
           GeneralNonIndexRequestBlockContextUpdater |
           RepositoryRequestBlockContextUpdater |
           SnapshotRequestBlockContextUpdater |
           DataStreamRequestBlockContextUpdater |
           TemplateRequestBlockContextUpdater |
           MultiIndexRequestBlockContextUpdater |
           RorApiRequestBlockContextUpdater =>
        onForbidden(request, NonEmptyList.one(OperationNotAllowed))
    }
  }

  private def onAliasNotFound[B <: BlockContext : BlockContextUpdater](request: EsRequest[B] with RequestContext.Aux[B]): Unit = {
    BlockContextUpdater[B] match {
      case AliasRequestBlockContextUpdater =>
        handleAliasNotFoundForAliasRequest(request.asInstanceOf[EsRequest[AliasRequestBlockContext] with RequestContext.Aux[AliasRequestBlockContext]])
      case FilterableMultiRequestBlockContextUpdater |
           FilterableRequestBlockContextUpdater |
           GeneralIndexRequestBlockContextUpdater |
           CurrentUserMetadataRequestBlockContextUpdater |
           GeneralNonIndexRequestBlockContextUpdater |
           RepositoryRequestBlockContextUpdater |
           SnapshotRequestBlockContextUpdater |
           DataStreamRequestBlockContextUpdater |
           TemplateRequestBlockContextUpdater |
           MultiIndexRequestBlockContextUpdater |
           RorApiRequestBlockContextUpdater =>
        onForbidden(request, NonEmptyList.one(OperationNotAllowed))
    }
  }

  private def onTemplateNotFound[B <: BlockContext : BlockContextUpdater](request: EsRequest[B] with RequestContext.Aux[B]): Unit = {
    BlockContextUpdater[B] match {
      case TemplateRequestBlockContextUpdater =>
        handleTemplateNotFoundForTemplateRequest(request.asInstanceOf[EsRequest[TemplateRequestBlockContext] with RequestContext.Aux[TemplateRequestBlockContext]])
      case FilterableMultiRequestBlockContextUpdater |
           FilterableRequestBlockContextUpdater |
           GeneralIndexRequestBlockContextUpdater |
           CurrentUserMetadataRequestBlockContextUpdater |
           GeneralNonIndexRequestBlockContextUpdater |
           RepositoryRequestBlockContextUpdater |
           SnapshotRequestBlockContextUpdater |
           DataStreamRequestBlockContextUpdater |
           AliasRequestBlockContextUpdater |
           MultiIndexRequestBlockContextUpdater |
           RorApiRequestBlockContextUpdater =>
        onForbidden(request, NonEmptyList.one(OperationNotAllowed))
    }
  }

  private def handleIndexNotFoundForGeneralIndexRequest(request: EsRequest[GeneralIndexRequestBlockContext] with RequestContext.Aux[GeneralIndexRequestBlockContext]): Unit = {
    val modificationResult = request.modifyWhenIndexNotFound
    handleModificationResult(request, modificationResult)
  }

  private def handleIndexNotFoundForSearchRequest(request: EsRequest[FilterableRequestBlockContext] with RequestContext.Aux[FilterableRequestBlockContext]): Unit = {
    val modificationResult = request.modifyWhenIndexNotFound
    handleModificationResult(request, modificationResult)
  }

  private def handleIndexNotFoundForMultiSearchRequest(request: EsRequest[FilterableMultiRequestBlockContext] with RequestContext.Aux[FilterableMultiRequestBlockContext]): Unit = {
    val modificationResult = request.modifyWhenIndexNotFound
    handleModificationResult(request, modificationResult)
  }

  private def handleIndexNotFoundForAliasRequest(request: EsRequest[AliasRequestBlockContext] with RequestContext.Aux[AliasRequestBlockContext]): Unit = {
    val modificationResult = request.modifyWhenIndexNotFound
    handleModificationResult(request, modificationResult)
  }

  private def handleAliasNotFoundForAliasRequest(request: EsRequest[AliasRequestBlockContext] with RequestContext.Aux[AliasRequestBlockContext]): Unit = {
    val modificationResult = request.modifyWhenAliasNotFound
    handleModificationResult(request, modificationResult)
  }

  private def handleTemplateNotFoundForTemplateRequest(request: EsRequest[TemplateRequestBlockContext] with RequestContext.Aux[TemplateRequestBlockContext]): Unit = {
    val modificationResult = request.modifyWhenTemplateNotFound
    handleModificationResult(request, modificationResult)
  }

  private def handleModificationResult(requestContext: RequestContext, modificationResult: ModificationResult): Unit = {
    modificationResult match {
      case ModificationResult.Modified =>
        proceed(requestContext)
      case ModificationResult.CannotModify =>
        onForbidden(requestContext, NonEmptyList.one(OperationNotAllowed))
      case ModificationResult.ShouldBeInterrupted =>
        onForbidden(requestContext, NonEmptyList.one(OperationNotAllowed))
      case CustomResponse(response) =>
        respond(requestContext, response)
      case UpdateResponse(updateFunc) =>
        proceed(requestContext, new UpdateResponseListener(updateFunc))
    }
  }

  private def configureResponseTransformations(responseTransformations: List[ResponseTransformation]): Unit = {
    responseTransformations.foreach {
      case FilteredResponseFields(responseFieldsRestrictions) =>
        esContext.channel.setResponseFieldRestrictions(responseFieldsRestrictions)
    }
  }

  private def proceed(requestContext: RequestContext, listener: ActionListener[ActionResponse] = esContext.listener): Unit = {
    logRequestProcessingTime(requestContext)
    addProperHeader()
    esContext.chain.continue(esContext, listener)
  }

  private def addProperHeader(): Unit = {
    if(esContext.action.isFieldCapsAction || esContext.action.isRollupAction || esContext.action.isGetSettingsAction)
      threadPool.getThreadContext.addSystemAuthenticationHeader(esContext.nodeName)
    else if (esContext.action.isXpackSecurityAction)
      threadPool.getThreadContext.addRorUserAuthenticationHeader(esContext.nodeName)
    else
      threadPool.getThreadContext.addXpackSecurityAuthenticationHeader(esContext.nodeName)
  }

  private def respond(requestContext: RequestContext, response: ActionResponse): Unit = {
    logRequestProcessingTime(requestContext)
    esContext.listener.onResponse(response)
  }

  private def logRequestProcessingTime(requestContext: RequestContext): Unit = {
    logger.debug(s"[${requestContext.id.toRequestId.show}] Request processing time: ${Duration.between(requestContext.timestamp, Instant.now()).toMillis}ms")
  }

  private class UpdateResponseListener(update: ActionResponse => Task[ActionResponse]) extends ActionListener[ActionResponse] {
    override def onResponse(response: ActionResponse): Unit = doPrivileged {
      val stashedContext = threadPool.getThreadContext.stashAndMergeResponseHeaders(esContext)
      update(response) runAsync {
        case Right(updatedResponse) =>
          stashedContext.restore()
          esContext.listener.onResponse(updatedResponse)
        case Left(ex) =>
          stashedContext.close()
          onFailure(new Exception(ex))
      }
    }

    override def onFailure(e: Exception): Unit = esContext.listener.onFailure(e)
  }
}