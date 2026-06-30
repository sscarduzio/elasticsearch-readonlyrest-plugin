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
package tech.beshu.ror.accesscontrol.audit.sink

import monix.eval.Task
import tech.beshu.ror.accesscontrol.audit.remote.AuditRemoteClusterConnectivityCheck
import tech.beshu.ror.accesscontrol.audit.remote.AuditRemoteClusterConnectivityCheck.Error.ConnectivityError
import tech.beshu.ror.accesscontrol.audit.sink.AuditSinkServiceCreator.InitializationError
import tech.beshu.ror.accesscontrol.domain.AuditCluster
import tech.beshu.ror.accesscontrol.domain.AuditCluster.RemoteAuditCluster
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
import tech.beshu.ror.es.services.{DataStreamBasedAuditSinkService, IndexBasedAuditSinkService}
import tech.beshu.ror.utils.RequestIdAwareLogging

sealed trait AuditSinkServiceCreator extends RequestIdAwareLogging {

  protected final def withConnectivityCheck[AUDIT_SERVICE](
      cluster: AuditCluster,
      httpClientsFactory: HttpClientsFactory,
      create: Task[AUDIT_SERVICE]
  ): Task[Either[AuditSinkServiceCreator.InitializationError, AUDIT_SERVICE]] = {
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
              Task
                .delay(
                  noRequestIdLogger.info(
                    s"Audit cluster connectivity check failed, but 'ignore_es_connectivity_problems: true' is set, so auditing will proceed: ${error.message}"
                  )
                )
                .flatMap(_ => create.map(Right(_)))
            case Left(error: ConnectivityError) =>
              Task.pure(
                Left(
                  InitializationError(
                    s"${error.message}. You can disable this check by setting 'ignore_es_connectivity_problems: true' in the audit cluster configuration"
                  )
                )
              )
            case Left(error: AuditRemoteClusterConnectivityCheck.Error.ConfigurationError) =>
              Task.pure(Left(InitializationError(error.message)))
          }
    }
  }

}

object AuditSinkServiceCreator extends RequestIdAwareLogging {

  final case class InitializationError(message: String)

}

trait IndexBasedAuditSinkServiceCreator extends AuditSinkServiceCreator {

  protected def index(cluster: AuditCluster): IndexBasedAuditSinkService

  final def createIndexService(
      cluster: AuditCluster,
      httpClientsFactory: HttpClientsFactory
  ): Task[Either[AuditSinkServiceCreator.InitializationError, IndexBasedAuditSinkService]] =
    withConnectivityCheck(cluster, httpClientsFactory, Task.delay(index(cluster)))

}

trait DataStreamBasedAuditSinkServiceCreator extends AuditSinkServiceCreator {

  protected def dataStream(cluster: AuditCluster): DataStreamBasedAuditSinkService

  final def createDataStreamService(
      cluster: AuditCluster,
      httpClientsFactory: HttpClientsFactory
  ): Task[Either[AuditSinkServiceCreator.InitializationError, DataStreamBasedAuditSinkService]] =
    withConnectivityCheck(cluster, httpClientsFactory, Task.delay(dataStream(cluster)))

}
