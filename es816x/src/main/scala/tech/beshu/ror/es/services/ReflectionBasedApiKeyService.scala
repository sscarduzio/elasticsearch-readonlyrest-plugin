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
import tech.beshu.ror.accesscontrol.domain.AuthorizationToken
import tech.beshu.ror.es.ApiKeyService
import tech.beshu.ror.es.utils.ActionListenerToTaskAdapter
import tech.beshu.ror.utils.AccessControllerHelper

class ReflectionBasedApiKeyService(threadPool: ThreadPool) extends ApiKeyService {

  private lazy val instance = ApiKeyServiceRef.getInstance.get

  private lazy val apiKeyType = {
    val classLoader = instance.getClass.getClassLoader
    val apiKeyTypeClass = Class.forName("org.elasticsearch.xpack.core.security.action.apikey.ApiKey$Type", true, classLoader)
    onClass(apiKeyTypeClass)
      .call("valueOf", "REST")
      .get[AnyRef]
  }

  override def validateToken(token: AuthorizationToken): Task[Boolean] = {
    parseApiKey(token)
      .map(authenticateApiKey)
      .getOrElse(Task.now(false))
  }

  private def parseApiKey(token: AuthorizationToken): Option[AnyRef] = {
    Option {
      on(instance)
        .call("parseApiKey", new SecureString(token.value.value.toArray), apiKeyType)
        .get[AnyRef]
    }
  }

  private def authenticateApiKey(apiKeyCredentials: AnyRef): Task[Boolean] = {
    val listener = new ActionListenerToTaskAdapter[AnyRef]
    on(instance).call("tryAuthenticate", threadPool.getThreadContext, apiKeyCredentials, listener)
    listener
      .result
      .map(isAuthenticated)
      .onErrorRecover {
        case ex: ElasticsearchSecurityException => false
        case ex => throw ex
      }
  }

  private def isAuthenticated(authenticationResult: AnyRef): Boolean = {
    Option(authenticationResult)
      .exists(result => on(result).call("isAuthenticated").get[Boolean])
  }
}

object ApiKeyServiceRef {
  private val Bridge = "org.elasticsearch.plugins.ApiKeyServiceBridge"

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
    val resPath = "org/elasticsearch/plugins/ApiKeyServiceBridge.class"
    val hits = candidates.flatMap { cl =>
      Option(cl.getResource(resPath)).map(u => s"${cl.getClass.getName} -> $u")
    }
    if (hits.nonEmpty) hits.mkString("FOUND in:\n  ", "\n  ", "") else "NOT FOUND in any candidate"
  }
}