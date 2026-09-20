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
import tech.beshu.ror.accesscontrol.audit.output.ConnectivityCheckedAuditOutputServiceCreator.InitializationError
import tech.beshu.ror.accesscontrol.audit.remote.AuditRemoteClusterConnectivityCheck
import tech.beshu.ror.accesscontrol.audit.remote.AuditRemoteClusterConnectivityCheck.Error.{
  ConfigurationError,
  ConnectivityError
}
import tech.beshu.ror.accesscontrol.domain.AuditCluster.{ConnectivityCheckMode, RemoteAuditCluster}
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, RequestId}
import tech.beshu.ror.es.services.{DataStreamBasedAuditOutputService, IndexBasedAuditOutputService}
import tech.beshu.ror.utils.RequestIdAwareLogging

sealed trait ConnectivityCheckedAuditOutputServiceCreator extends RequestIdAwareLogging {

  protected def connectivityCheck: AuditRemoteClusterConnectivityCheck

  protected final def withConnectivityCheck[AUDIT_SERVICE](
      cluster: AuditCluster,
      create: Task[AUDIT_SERVICE]
  )(
      using RequestId
  ): Task[Either[InitializationError, AUDIT_SERVICE]] = {
    cluster match {
      case AuditCluster.LocalAuditCluster =>
        create.map(Right(_))
      case remote: RemoteAuditCluster =>
        remote.connectivityCheckMode match {
          case ConnectivityCheckMode.Required =>
            createAfterCheck(remote, create) { error =>
              initializationError(error.message)
            }
          case ConnectivityCheckMode.BestEffort =>
            createAfterCheck(remote, create) { error =>
              for {
                service <- create
                _ <- logger.dInfo(
                  s"Audit cluster connectivity check failed, but a connectivity problem of this cluster does not stop the auditing: ${error.message}"
                )
              } yield Right(service)
            }
          case ConnectivityCheckMode.Disabled =>
            create.map(Right(_))
        }
    }
  }

  private def createAfterCheck[AUDIT_SERVICE](
      cluster: RemoteAuditCluster,
      create: Task[AUDIT_SERVICE]
  )(
      onConnectivityProblem: ConnectivityError => Task[Either[InitializationError, AUDIT_SERVICE]]
  ): Task[Either[InitializationError, AUDIT_SERVICE]] = {
    connectivityCheck
      .check(cluster)
      .flatMap {
        case Right(())                       => create.map(Right(_))
        case Left(error: ConnectivityError)  => onConnectivityProblem(error)
        case Left(error: ConfigurationError) => initializationError(error.message)
      }
  }

  private def initializationError(message: String) = {
    Task.pure(Left(InitializationError(message)))
  }

}

object ConnectivityCheckedAuditOutputServiceCreator {

  final case class InitializationError(message: String)

}

final class ConnectivityCheckedIndexBasedAuditOutputServiceCreator(
    underlying: IndexBasedAuditOutputServiceCreator,
    override protected val connectivityCheck: AuditRemoteClusterConnectivityCheck
) extends ConnectivityCheckedAuditOutputServiceCreator {

  def createIndexService(cluster: AuditCluster)(
      using RequestId
  ): Task[Either[InitializationError, IndexBasedAuditOutputService]] =
    withConnectivityCheck(cluster, Task.delay(underlying.index(cluster)))

}

final class ConnectivityCheckedDataStreamBasedAuditOutputServiceCreator(
    underlying: DataStreamBasedAuditOutputServiceCreator,
    override protected val connectivityCheck: AuditRemoteClusterConnectivityCheck
) extends ConnectivityCheckedAuditOutputServiceCreator {

  def createDataStreamService(cluster: AuditCluster)(
      using RequestId
  ): Task[Either[InitializationError, DataStreamBasedAuditOutputService]] =
    withConnectivityCheck(cluster, Task.delay(underlying.dataStream(cluster)))

}
