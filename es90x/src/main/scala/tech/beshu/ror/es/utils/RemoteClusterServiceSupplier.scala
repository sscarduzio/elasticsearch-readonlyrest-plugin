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

import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.client.internal.node.NodeClient
import org.elasticsearch.transport.RemoteClusterService
import org.joor.Reflect.on
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

import java.util.function.Supplier
import scala.util.{Failure, Success, Try}

class RemoteClusterServiceSupplier(nodeClient: NodeClient)
  extends Supplier[Option[RemoteClusterService]] with Logging {

  override def get(): Option[RemoteClusterService] = {
    extractRemoteClusterService() match {
      case Success(remoteClusterService) =>
        remoteClusterService
      case Failure(ex) =>
        logger.error("Cannot extract RemoteClusterService from NodeClient", ex)
        None
    }
  }

  private def extractRemoteClusterService(): Try[Option[RemoteClusterService]] = doPrivileged {
    Try(Option(on(nodeClient).get[RemoteClusterService]("remoteClusterService")))
  }
}
