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
import org.joor.Reflect.{on, onClass}
import tech.beshu.ror.accesscontrol.domain.{AuthorizationToken, RequestId}
import tech.beshu.ror.es.utils.ActionListenerToTaskAdapter
import tech.beshu.ror.utils.{AccessControllerHelper, RequestIdAwareLogging}

import scala.util.{Failure, Success, Try, Using}

class ReflectionBasedServiceAccountTokenService extends ServiceAccountTokenService {

  private lazy val underlying = ServiceAccountServiceRef.getInstance match {
    case Success(ref) => new ServiceAccountTokenServiceRefAvailable(ref)
    case Failure(ex) => new ServiceAccountTokenServiceRefNotAvailable(ex)
  }

  override def validateToken(token: AuthorizationToken)
                            (implicit requestId: RequestId): Task[Boolean] =
    underlying.validateToken(token)
}

private class ServiceAccountTokenServiceRefAvailable(serviceAccountServiceRef: AnyRef)
  extends ServiceAccountTokenService with RequestIdAwareLogging {

  override def validateToken(token: AuthorizationToken)
                            (implicit requestId: RequestId): Task[Boolean] = {
    parseToken(token) match {
      case Success(Some(serviceAccountToken)) =>
        authenticateToken(serviceAccountToken)
      case Success(None) =>
        logger.warn("Token cannot be parsed as ServiceAccountToken")
        Task.now(false)
      case Failure(ex) =>
        logger.warn("Token cannot be parsed as ServiceAccountToken", ex)
        Task.now(false)
    }
  }

  private def parseToken(token: AuthorizationToken): Try[Option[AnyRef]] =
    Using(new SecureString(token.value.value.toArray)) { secureString =>
      Option {
        on(serviceAccountServiceRef)
          .call("tryParseToken", secureString)
          .get[AnyRef]
      }
    }

  private def authenticateToken(serviceAccountToken: AnyRef): Task[Boolean] = {
    val listener = new ActionListenerToTaskAdapter[AnyRef]
    on(serviceAccountServiceRef).call("authenticateToken", serviceAccountToken, "any", listener)
    listener
      .result
      .map(ref => Option(ref).isDefined)
      .onErrorRecover {
        case _: ElasticsearchSecurityException => false
        case ex => throw ex
      }
  }
}

private class ServiceAccountTokenServiceRefNotAvailable(cause: Throwable) extends ServiceAccountTokenService {

  override def validateToken(token: AuthorizationToken)
                            (implicit requestId: RequestId): Task[Boolean] = Task.raiseError {
    new Exception("ServiceAccount Service Ref is not available. Please report the issue!", cause)
  }
}

private object ServiceAccountServiceRef {
  private val bridge = "org.elasticsearch.plugins.ServiceAccountServiceBridge"

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
