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
package tech.beshu.ror.accesscontrol.audit.output

import monix.eval.Task
import tech.beshu.ror.accesscontrol.audit.output.AuditOutputServiceCreator.InitializationError
import tech.beshu.ror.accesscontrol.audit.remote.AuditRemoteClusterConnectivityCheck
import tech.beshu.ror.accesscontrol.audit.remote.AuditRemoteClusterConnectivityCheck.Error.ConnectivityError
import tech.beshu.ror.accesscontrol.domain.AuditCluster.RemoteAuditCluster
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, RequestId}
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
import tech.beshu.ror.es.services.{DataStreamBasedAuditOutputService, IndexBasedAuditOutputService}
import tech.beshu.ror.utils.RequestIdAwareLogging

sealed trait AuditOutputServiceCreator extends RequestIdAwareLogging {

  protected final def withConnectivityCheck[AUDIT_SERVICE](
      cluster: AuditCluster,
      httpClientsFactory: HttpClientsFactory,
      create: Task[AUDIT_SERVICE]
  )(
      using RequestId
  ): Task[Either[InitializationError, AUDIT_SERVICE]] = {
    cluster match {
      case AuditCluster.LocalAuditCluster =>
        create.map(Right(_))
      case remote: RemoteAuditCluster =>
        new AuditRemoteClusterConnectivityCheck(httpClientsFactory)
          .check(remote)
          .flatMap {
            case Right(()) =>
              create.map(Right(_))
            case Left(error: ConnectivityError) if remote.ignoreClusterConnectivityProblems =>
              for {
                service <- create
                _ <- logger.dInfo(
                  s"Audit cluster connectivity check failed, but 'ignore_es_connectivity_problems: true' is set, so auditing will proceed: ${error.message}"
                )
              } yield Right(service)
            case Left(error: ConnectivityError) =>
              initializationError(
                s"${error.message}. You can disable this check by setting 'ignore_es_connectivity_problems: true' in the audit cluster configuration"
              )
            case Left(error: AuditRemoteClusterConnectivityCheck.Error.ConfigurationError) =>
              initializationError(error.message)
          }
    }
  }

  private def initializationError(message: String) = {
    Task.pure(Left(InitializationError(message)))
  }

}

object AuditOutputServiceCreator {

  final case class InitializationError(message: String)

}

trait IndexBasedAuditOutputServiceCreator extends AuditOutputServiceCreator {

  protected def index(cluster: AuditCluster): IndexBasedAuditOutputService

  final def createIndexService(
      cluster: AuditCluster,
      httpClientsFactory: HttpClientsFactory
  )(
      using RequestId
  ): Task[Either[InitializationError, IndexBasedAuditOutputService]] =
    withConnectivityCheck(cluster, httpClientsFactory, Task.delay(index(cluster)))

}

trait DataStreamBasedAuditOutputServiceCreator extends AuditOutputServiceCreator {

  protected def dataStream(cluster: AuditCluster): DataStreamBasedAuditOutputService

  final def createDataStreamService(
      cluster: AuditCluster,
      httpClientsFactory: HttpClientsFactory
  )(
      using RequestId
  ): Task[Either[InitializationError, DataStreamBasedAuditOutputService]] =
    withConnectivityCheck(cluster, httpClientsFactory, Task.delay(dataStream(cluster)))

}
