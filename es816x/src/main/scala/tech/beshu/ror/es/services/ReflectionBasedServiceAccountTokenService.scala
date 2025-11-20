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
import tech.beshu.ror.accesscontrol.domain.AuthorizationToken
import tech.beshu.ror.es.ServiceAccountTokenService
import tech.beshu.ror.es.utils.ActionListenerToTaskAdapter
import tech.beshu.ror.utils.AccessControllerHelper

class ReflectionBasedServiceAccountTokenService extends ServiceAccountTokenService {

  private lazy val instance = ServiceAccountServiceRef.getInstance.get

  override def validateToken(token: AuthorizationToken): Task[Boolean] = {
    tryParseToken(token)
      .map(authenticateToken)
      .getOrElse(Task.now(false))
  }

  private def tryParseToken(token: AuthorizationToken): Option[AnyRef] = {
    Option {
      on(instance)
        .call("tryParseToken", new SecureString(token.value.value.toArray))
        .get[AnyRef]
    }
  }

  private def authenticateToken(serviceAccountToken: AnyRef): Task[Boolean] = {
    val listener = new ActionListenerToTaskAdapter[AnyRef]
    on(instance).call("authenticateToken", serviceAccountToken, "any", listener)
    listener
      .result
      .map { ref =>
        Option(ref) match {
          case Some(_) => true
          case None => false
        }
      }
      .onErrorRecover {
        case ex: ElasticsearchSecurityException => false
        case ex => throw ex
      }
  }
}

object ServiceAccountServiceRef {
  private val Bridge = "org.elasticsearch.plugins.ServiceAccountServiceBridge"

  private def candidates: List[ClassLoader] = AccessControllerHelper.doPrivileged {
    List(
      Thread.currentThread().getContextClassLoader,
      this.getClass.getClassLoader,
      Option(this.getClass.getClassLoader).map(_.getParent).orNull,
      ClassLoader.getSystemClassLoader,
      ClassLoader.getPlatformClassLoader
    ).filter(_ != null).distinct
  }

  private def loadBridgeClass(): Option[Class[_]] =
    candidates.view.flatMap { cl =>
      try Some(Class.forName(Bridge, /*initialize*/ false, cl))
      catch {
        case _: Throwable => None
      }
    }.headOption

  def available: Boolean = loadBridgeClass().isDefined

  def getInstance: Option[AnyRef] =
    loadBridgeClass().flatMap { cls =>
      try Option(onClass(cls).call("get").get[AnyRef])
      catch {
        case _: Throwable => None
      }
    }

  def clear(): Unit =
    loadBridgeClass().foreach { cls =>
      try onClass(cls).call("clear") catch {
        case _: Throwable => ()
      }
    }

  def debugProbe(): String = {
    val resPath = "org/elasticsearch/plugins/ServiceAccountServiceBridge.class"
    val hits = candidates.flatMap { cl =>
      Option(cl.getResource(resPath)).map(u => s"${cl.getClass.getName} -> $u")
    }
    if (hits.nonEmpty) hits.mkString("FOUND in:\n  ", "\n  ", "") else "NOT FOUND in any candidate"
  }
}