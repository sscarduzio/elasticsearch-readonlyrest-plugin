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
package tech.beshu.ror.es.utils

import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.rest.action.cat.RestCatAction
import org.elasticsearch.rest.{RestChannel, RestHandler, RestRequest}
import org.joor.Reflect.on
import tech.beshu.ror.es.RorRestChannel
import tech.beshu.ror.es.actions.wrappers._cat.rest.RorWrappedRestCatAction
import tech.beshu.ror.es.utils.ThreadContextOps.createThreadContextOps
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

import scala.util.Try

class ChannelInterceptingRestHandlerDecorator private(val underlying: RestHandler,
                                                      settings: Settings)
  extends RestHandler {

  private val wrapped = doPrivileged {
    wrapSomeActions(underlying)
  }

  override def handleRequest(request: RestRequest, channel: RestChannel, client: NodeClient): Unit = {
    val rorRestChannel = new RorRestChannel(channel)
    ThreadRepo.setRestChannel(rorRestChannel)
    addRorUserAuthenticationHeaderForInCaseOfSecurityRequest(request, client)
    wrapped.handleRequest(request, rorRestChannel, client)
  }

  override def canTripCircuitBreaker: Boolean = underlying.canTripCircuitBreaker

  override def supportsContentStream(): Boolean = underlying.supportsContentStream()

  private def wrapSomeActions(ofHandler: RestHandler) = {
    unwrapWithSecurityRestFilterIfNeeded(ofHandler) match {
      case action: RestCatAction => new RorWrappedRestCatAction(settings, action)
      case action => action
    }
  }

  private def unwrapWithSecurityRestFilterIfNeeded(restHandler: RestHandler) = {
    restHandler match {
      case action if action.getClass.getName.contains("SecurityRestFilter") =>
        tryToGetUnderlyingRestHandler(action, "restHandler")
          .getOrElse(action)
      case _ =>
        restHandler
    }
  }

  private def tryToGetUnderlyingRestHandler(restHandler: RestHandler,
                                            fieldName: String) = {
    Try(on(restHandler).get[RestHandler](fieldName))
  }

  private def addRorUserAuthenticationHeaderForInCaseOfSecurityRequest(request: RestRequest,
                                                                       client: NodeClient): Unit = {
    if (request.path().contains("/_security") || request.path().contains("/_xpack/security")) {
      client
        .threadPool().getThreadContext
        .addRorUserAuthenticationHeader(client.getLocalNodeId)
    }
  }

}

object ChannelInterceptingRestHandlerDecorator {
  def create(restHandler: RestHandler,
             settings: Settings): ChannelInterceptingRestHandlerDecorator = restHandler match {
    case alreadyDecoratedHandler: ChannelInterceptingRestHandlerDecorator => alreadyDecoratedHandler
    case handler => new ChannelInterceptingRestHandlerDecorator(handler, settings)
  }
}