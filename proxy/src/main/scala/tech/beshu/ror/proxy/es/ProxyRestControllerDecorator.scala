/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.UnaryOperator

import monix.eval.Coeval
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.common.logging.DeprecationLogger
import org.elasticsearch.common.util.concurrent.ThreadContext
import org.elasticsearch.indices.breaker.CircuitBreakerService
import org.elasticsearch.rest._
import org.elasticsearch.usage.UsageService
import org.joor.Reflect._
import tech.beshu.ror.proxy.es.rest.RestGenericRequestAction

class ProxyRestControllerDecorator(restController: RestController)
  extends RestController(
    on(restController).get[java.util.Set[RestHeaderDefinition]]("headersToCopy"),
    on(restController).get[UnaryOperator[RestHandler]]("handlerWrapper"),
    on(restController).get[NodeClient]("client"),
    on(restController).get[CircuitBreakerService]("circuitBreakerService"),
    on(restController).get[UsageService]("usageService")
  ) {

  private var restGenericRequestAction: Option[RestGenericRequestAction] = None

  private val delayedRegistrations = new CopyOnWriteArrayList[Coeval[Unit]]()

  def setGenericRequestAction(action: RestGenericRequestAction): Unit = {
    restGenericRequestAction = Some(action)
  }

  def flushRegistrations(): Unit = {
    delayedRegistrations.forEach(_.apply())
    delayedRegistrations.clear()
  }

  override def registerAsDeprecatedHandler(method: RestRequest.Method,
                                           path: String,
                                           handler: RestHandler,
                                           deprecationMessage: String,
                                           logger: DeprecationLogger): Unit = {
    delayedRegistrations.add(Coeval {
      super.registerAsDeprecatedHandler(method, path, handler, deprecationMessage, logger)
    })
  }

  override def registerWithDeprecatedHandler(method: RestRequest.Method,
                                             path: String,
                                             handler: RestHandler,
                                             deprecatedMethod: RestRequest.Method,
                                             deprecatedPath: String,
                                             logger: DeprecationLogger): Unit = {
    delayedRegistrations.add(Coeval {
      super.registerWithDeprecatedHandler(method, path, handler, deprecatedMethod, deprecatedPath, logger)
    })
  }

  override def registerHandler(method: RestRequest.Method, path: String, handler: RestHandler): Unit = {
    delayedRegistrations.add(Coeval {
      restGenericRequestAction match {
        case Some(action) if path.startsWith("/_nodes") => // todo: how to specify it better?
          super.registerHandler(method, path, action)
        case _ =>
          // todo: throw illegal state when action is not set
          super.registerHandler(method, path, handler)
      }
    })
  }

  override def dispatchRequest(request: RestRequest, channel: RestChannel, threadContext: ThreadContext): Unit =
    super.dispatchRequest(request, channel, threadContext)

  override def dispatchBadRequest(channel: RestChannel, threadContext: ThreadContext, cause: Throwable): Unit =
    super.dispatchBadRequest(channel, threadContext, cause)
}
