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

import org.elasticsearch.action.support.{ActionFilter, ActionFilterChain, TransportAction}
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.tasks.Task
import org.joor.Reflect.on
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

import scala.collection.JavaConverters._
import scala.language.implicitConversions

class NodeClientOps(val nodeClient: NodeClient) extends AnyVal {

  def deactivateXPackFilter(): Unit = doPrivileged {
    on(nodeClient)
      .get[java.util.Map[AnyRef, TransportAction[ActionRequest, ActionResponse]]]("actions")
      .asScala.toMap
      .foreach { case (_, action) =>
        val filters = on(action).get[Array[ActionFilter]]("filters")
        val xpackFilterIndex = filters.indexWhere(filer => filer.getClass.getName == "org.elasticsearch.xpack.security.action.filter.SecurityActionFilter")
        if(xpackFilterIndex >= 0) {
          filters.update(xpackFilterIndex, NodeClientOps.dummyActionFilter)
        }
      }
  }
}

object NodeClientOps {

  implicit def toNodeClientOps(nodeClient: NodeClient): NodeClientOps = new NodeClientOps(nodeClient)

  private val dummyActionFilter = new ActionFilter {
    override def order(): Int = 0

    override def apply[Request <: ActionRequest, Response <: ActionResponse](task: Task,
                                                                             action: String,
                                                                             request: Request,
                                                                             listener: ActionListener[Response],
                                                                             chain: ActionFilterChain[Request, Response]): Unit =
      chain.proceed(task, action, request, listener)
  }
}
