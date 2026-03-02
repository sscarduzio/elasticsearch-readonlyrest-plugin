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
package tech.beshu.ror.es.services

import monix.eval.Task
import org.elasticsearch.ElasticsearchSecurityException
import org.elasticsearch.common.settings.SecureString
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect.{on, onClass}
import tech.beshu.ror.accesscontrol.domain.{AuthorizationToken, RequestId}
import tech.beshu.ror.es.ApiKeyService
import tech.beshu.ror.es.utils.ActionListenerToTaskAdapter
import tech.beshu.ror.utils.{AccessControllerHelper, RequestIdAwareLogging}

import scala.util.{Failure, Success, Try, Using}

class ReflectionBasedApiKeyService(threadPool: ThreadPool) extends ApiKeyService {

  private lazy val underlying = ApiKeyServiceRef.getInstance match {
    case Success(ref) => new ApiKeyServiceRefAvailable(ref, threadPool)
    case Failure(ex) => new ApiKeyServiceRefNotAvailable(ex)
  }

  override def validateToken(token: AuthorizationToken)
                            (implicit requestId: RequestId): Task[Boolean] =
    underlying.validateToken(token)
}

private class ApiKeyServiceRefAvailable(apiKeyServiceRef: Any,
                                        threadPool: ThreadPool)
  extends ApiKeyService with RequestIdAwareLogging {

  private val apiKeyType: Try[AnyRef] = Try {
    val classLoader = apiKeyServiceRef.getClass.getClassLoader
    val apiKeyTypeClass = Class.forName("org.elasticsearch.xpack.core.security.action.apikey.ApiKey$Type", true, classLoader)
    onClass(apiKeyTypeClass)
      .call("valueOf", "REST")
      .get[AnyRef]
  }

  override def validateToken(token: AuthorizationToken)
                            (implicit requestId: RequestId): Task[Boolean] = {
    parseApiKey(token) match {
      case Success(apiKey) =>
        authenticateApiKey(apiKey)
      case Failure(ex) =>
        logger.warn("Token cannot be parsed as ApiKey", ex)
        Task.now(false)
    }
  }

  private def parseApiKey(token: AuthorizationToken): Try[AnyRef] =
    Using(new SecureString(token.value.value.toArray)) { secureString =>
      apiKeyType
        .flatMap { `type` =>
          Try(on(apiKeyServiceRef).call("parseApiKey", secureString, null, `type`).get[AnyRef])
        }
        .get
    }

  private def authenticateApiKey(apiKeyCredentials: AnyRef): Task[Boolean] = {
    val listener = new ActionListenerToTaskAdapter[AnyRef]
    on(apiKeyServiceRef).call("tryAuthenticate", threadPool.getThreadContext, apiKeyCredentials, listener)
    listener
      .result
      .map(isAuthenticated)
      .onErrorRecover {
        case _: ElasticsearchSecurityException => false
        case ex => throw ex
      }
  }

  private def isAuthenticated(authenticationResult: AnyRef): Boolean = {
    Option(authenticationResult)
      .exists(result => on(result).call("isAuthenticated").get[Boolean])
  }
}

private class ApiKeyServiceRefNotAvailable(cause: Throwable) extends ApiKeyService {

  override def validateToken(token: AuthorizationToken)
                            (implicit requestId: RequestId): Task[Boolean] = Task.raiseError {
    new Exception("ApiKey Service Ref is not available. Please report the issue!", cause)
  }
}

private object ApiKeyServiceRef {
  private val bridge = "org.elasticsearch.plugins.ApiKeyServiceBridge"

  def getInstance: Try[AnyRef] =
    loadBridgeClass()
      .map(c => onClass(c).call("get").get[AnyRef])

  private def loadBridgeClass(): Try[Class[_]] =
    classLoaderCandidates.view
      .flatMap { classLoader => Try(Class.forName(bridge, false, classLoader)).toOption }
      .headOption match {
      case Some(classLoader) => Success(classLoader)
      case None => Failure(new IllegalStateException(s"Cannot load $bridge class"))
    }

  private def classLoaderCandidates: List[ClassLoader] = AccessControllerHelper.doPrivileged {
    List(
      Option(Thread.currentThread().getContextClassLoader),
      Option(this.getClass.getClassLoader),
      Option(this.getClass.getClassLoader).flatMap(c => Option(c.getParent)),
      Option(ClassLoader.getSystemClassLoader),
      Option(ClassLoader.getPlatformClassLoader)
    ).flatten.distinct
  }

}
