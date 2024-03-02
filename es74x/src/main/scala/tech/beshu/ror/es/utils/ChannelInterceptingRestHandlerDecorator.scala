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
import org.elasticsearch.rest.action.cat.RestCatAction
import org.elasticsearch.rest.{RestChannel, RestHandler, RestRequest}
import org.joor.Reflect.on
import tech.beshu.ror.es.RorRestChannel
import tech.beshu.ror.es.actions.wrappers._cat.rest.RorWrappedRestCatAction
import tech.beshu.ror.es.utils.ThreadContextOps.createThreadContextOps
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import java.lang.reflect.{Proxy => JProxy}
import java.lang.reflect.{InvocationHandler, Method}
import scala.util.Try

class ChannelInterceptingRestHandlerDecorator private(val underlying: RestHandler)
  extends RestHandler with InvocationHandler {

  private val wrapped = doPrivileged {
    wrapSomeActions(underlying)
  }

  // This is a hack because in ES 7.4.x there is no `allowsUnsafeBuffers` method. In the next minor ES version
  // (in the same ROR es module) the method is present. We could create a new module, but maybe there is no sense
  // in this case. So, we need some kind of dynamic decorator. It can be achieved using Java Dynamic Proxy.
  // The solution is used only in this module (in other modules, there is no such issue).
  override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = {
    method.getName match {
      case "handleRequest" =>
        args.toList match {
          case request :: channel :: client :: Nil =>
            handleRequest(
              request.asInstanceOf[RestRequest],
              channel.asInstanceOf[RestChannel],
              client.asInstanceOf[NodeClient]
            ).asInstanceOf[AnyRef]
          case _ =>
            throw new IllegalStateException("Unexpected arguments list in 'handleRequest' invocation")
        }
      case _ =>
        method.invoke(underlying, args: _*)
    }
  }

  override def handleRequest(request: RestRequest, channel: RestChannel, client: NodeClient): Unit = {
    val rorRestChannel = new RorRestChannel(channel)
    ThreadRepo.setRestChannel(rorRestChannel)
    addRorUserAuthenticationHeaderForInCaseOfSecurityRequest(request, client)
    wrapped.handleRequest(request, rorRestChannel, client)
  }

  private def wrapSomeActions(ofHandler: RestHandler) = {
    unwrapWithSecurityRestFilterIfNeeded(ofHandler) match {
      case action: RestCatAction => new RorWrappedRestCatAction(action)
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
  def create(restHandler: RestHandler): RestHandler = restHandler match {
    case alreadyDecoratedHandler if JProxy.isProxyClass(alreadyDecoratedHandler.getClass) =>
      alreadyDecoratedHandler
    case handler =>
      createChannelInterceptingRestHandlerDecorator(handler)
  }

  private def createChannelInterceptingRestHandlerDecorator(handler: RestHandler) = {
      JProxy
        .newProxyInstance(
          this.getClass.getClassLoader,
          Array(classOf[RestHandler]),
          new ChannelInterceptingRestHandlerDecorator(handler)
        )
        .asInstanceOf[RestHandler]
  }
}
