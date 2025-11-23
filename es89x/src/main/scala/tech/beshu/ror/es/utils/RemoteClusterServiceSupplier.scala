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

import tech.beshu.ror.utils.RequestIdAwareLogging
import org.elasticsearch.repositories.{RepositoriesService, VerifyNodeRepositoryAction}
import org.elasticsearch.transport.{RemoteClusterService, TransportService}
import org.joor.Reflect.on
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

import java.util.function.Supplier
import scala.util.{Failure, Success, Try}

class RemoteClusterServiceSupplier(repositoriesServiceSupplier: Supplier[RepositoriesService])
  extends Supplier[Option[RemoteClusterService]] with RequestIdAwareLogging {

  override def get(): Option[RemoteClusterService] = {
    for {
      repositoriesService <- Option(repositoriesServiceSupplier.get())
      remoteClusterService <- extractTransportServiceFrom(repositoriesService) match {
        case Success(transportService) =>
          Option(transportService.getRemoteClusterService)
        case Failure(ex) =>
          noRequestIdLogger.error("Cannot extract RemoteClusterService from RepositoriesService", ex)
          None
      }
    } yield remoteClusterService
  }

  private def extractTransportServiceFrom(repositoriesService: RepositoriesService) = doPrivileged {
    for {
      action <- getVerifyNodeRepositoryActionFrom(repositoriesService)
      transportService <- getTransportServiceFrom(action)
    } yield transportService
  }

  private def getVerifyNodeRepositoryActionFrom(repositoriesService: RepositoriesService) = Try {
    on(repositoriesService).get[VerifyNodeRepositoryAction]("verifyAction")
  }

  private def getTransportServiceFrom(action: VerifyNodeRepositoryAction) = Try {
    on(action).get[TransportService]("transportService")
  }
}
