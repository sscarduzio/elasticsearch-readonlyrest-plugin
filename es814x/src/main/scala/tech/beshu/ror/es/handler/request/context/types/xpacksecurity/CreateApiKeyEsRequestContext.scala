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
package tech.beshu.ror.es.handler.request.context.types.xpacksecurity

import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect.onClass
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult.Modified
import tech.beshu.ror.es.handler.request.context.types.ReflectionBasedActionRequest
import tech.beshu.ror.es.handler.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.syntax.Set
import tech.beshu.ror.utils.AccessControllerHelper

class CreateApiKeyEsRequestContext private(actionRequest: ActionRequest,
                                          esContext: EsContext,
                                          clusterService: RorClusterService,
                                          override implicit val threadPool: ThreadPool)
  extends BaseEsRequestContext[GeneralNonIndexRequestBlockContext](esContext, clusterService)
    with EsRequest[GeneralNonIndexRequestBlockContext] {

  override def initialBlockContext: GeneralNonIndexRequestBlockContext = GeneralNonIndexRequestBlockContext(
    requestContext = this,
    userMetadata = UserMetadata.from(this),
    responseHeaders = Set.empty,
    responseTransformations = List.empty
  )

  override protected def modifyRequest(blockContext: GeneralNonIndexRequestBlockContext): ModificationResult = {
    actionRequest.toString
    val instance = ServiceAccountServiceRef.getInstance
    instance.toString
    Modified
  }
}

object CreateApiKeyEsRequestContext {

  def unapply(arg: ReflectionBasedActionRequest): Option[CreateApiKeyEsRequestContext] = {
    if (arg.esContext.actionRequest.getClass.getSimpleName.startsWith("CreateApiKeyRequest")) {
      Some(new CreateApiKeyEsRequestContext(arg.esContext.actionRequest, arg.esContext, arg.clusterService, arg.threadPool))
    } else {
      None
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
      catch { case _: Throwable => None }
    }.headOption

  def available: Boolean = loadBridgeClass().isDefined

  def getInstance: Option[AnyRef] =
    loadBridgeClass().flatMap { cls =>
      try Option(onClass(cls).call("get").get[AnyRef])
      catch { case _: Throwable => None }
    }

  def clear(): Unit =
    loadBridgeClass().foreach { cls =>
      try onClass(cls).call("clear") catch { case _: Throwable => () }
    }

  def debugProbe(): String = {
    val resPath = "org/elasticsearch/xpack/security/ServiceAccountServiceBridge.class"
    val hits = candidates.flatMap { cl =>
      Option(cl.getResource(resPath)).map(u => s"${cl.getClass.getName} -> $u")
    }
    if (hits.nonEmpty) hits.mkString("FOUND in:\n  ", "\n  ", "") else "NOT FOUND in any candidate"
  }
}