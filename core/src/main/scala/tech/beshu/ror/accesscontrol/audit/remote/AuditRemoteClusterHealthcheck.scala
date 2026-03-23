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
package tech.beshu.ror.accesscontrol.audit.remote

import cats.Show
import cats.data.NonEmptyList
import cats.effect.Resource
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import io.circe.Decoder
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.AuditCluster.{AuditClusterNode, NodeCredentials, RemoteAuditCluster}
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.{Config, HttpClient}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RefinedUtils.positiveFiniteDuration
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.utils.ScalaOps.retryBackoffEither

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.control.NonFatal

final class AuditRemoteClusterHealthcheck(httpClientsFactory: HttpClientsFactory) extends RequestIdAwareLogging {

  import AuditRemoteClusterHealthcheck.*
  import AuditRemoteClusterHealthcheck.given Show[AuditClusterNode]

  def check(cluster: RemoteAuditCluster): Task[Either[String, Unit]] = {
    implicit val requestId: RequestId = RequestId(UUID.randomUUID().toString)
    val auditClusterNodes = cluster.nodes.toNonEmptyList
    createHttpClient(auditClusterNodes)
      .use { httpClient =>
        Task.parTraverseUnordered(auditClusterNodes.toList.zipWithIndex) { case (node, idx) =>
          withRetries(getNodeInfo(cluster, httpClient, node))
            .map(response => (response, idx))
        }
      }
      .map { auditNodesInfo =>
        val (maybeConnectionErrors, maybeAuditNodeInfos) = {
          auditNodesInfo
            .sortBy { // sorting to have deterministic ordering of errors
              case (_, idx) => idx
            }
            .partitionMap {
              case (response, idx) => response
            }
            .bimap(NonEmptyList.fromList, NonEmptyList.fromList)
        }
        val detailedConnectionErrors = maybeConnectionErrors.map(_.toList.map(_.show).show)
        (for {
          auditNodeInfos <- maybeAuditNodeInfos.toRight(s"No health node detected in remote cluster. ${detailedConnectionErrors.get}")
          _ <- detailedConnectionErrors.toLeft(())
          auditNodesPerEsCluster = auditNodeInfos.groupBy(_.info.clusterUuid)
          _ <- Either.cond(auditNodesPerEsCluster.size == 1, (), "Configured remote cluster for audit contains ES nodes belonging to different ES clusters. One audit sink can use only nodes from one cluster. See https://docs.readonlyrest.com/elasticsearch/audit#custom-audit-cluster")
        } yield ())
          .leftMap {
            details => s"Audit cluster healthcheck failed for remote cluster ${cluster.nodes.toList.map(_.show).show}. Details: $details"
          }
      }
      .recover {
        case NonFatal(ex) =>
          logger.error("Unexpected error while remote audit cluster healthcheck", ex)
          Left("Unexpected error while remote audit cluster healthcheck")
      }
  }

  private def createHttpClient(auditClusterNodes: NonEmptyList[AuditClusterNode]) = {
    val maxPoolSize = 10
    val httpConfig = httpClientConfig(poolSize = Refined.unsafeApply(Math.max(1, Math.min(auditClusterNodes.size, maxPoolSize))))
    Resource.make(Task.delay(httpClientsFactory.create(httpConfig)))(_.close())
  }

  private def getNodeInfo(cluster: RemoteAuditCluster,
                          httpClient: HttpClient,
                          node: AuditClusterNode)(implicit requestId: RequestId): Task[Either[ConnectionError, AuditNodeInfo]] = {
    httpClient
      .send(healthCheckRequest(node, cluster.credentials))
      .map[Either[ConnectionError, AuditNodeInfo]] { response =>
        for {
          _ <- Either.cond(response.status == 200, (), ConnectionError.UnexpectedResponse(node, s"Unexpected status code: ${response.status} for GET cluster info request"))
          responseJson <- io.circe.parser.parse(response.body).leftMap(_ => ConnectionError.UnexpectedResponse(node, "Response is not a valid JSON"))
          clusterInfo <- responseJson.as[ClusterInfoResponse].leftMap {
            _ => ConnectionError.UnexpectedResponse(node, s"Invalid response for GET cluster info request")
          }
        } yield AuditNodeInfo(node, clusterInfo)
      }
      .recover {
        case NonFatal(ex) =>
          logger.error(s"Unexpected connection error while fetching cluster info from node ${node.show}", ex)
          Left(ConnectionError.UnexpectedConnectionError(node, ex))
      }
  }

  private def healthCheckRequest(node: AuditClusterNode, credentials: Option[NodeCredentials]): HttpClient.Request = {
    HttpClient.Request(
      HttpClient.Method.Get,
      node.toUrl,
      headers =
        credentials.map { c =>
          val header = toBasicAuthHeader(c)
          (header.name.value.value, header.value.value)
        }.toMap
    )
  }

  private def toBasicAuthHeader(nodeCredentials: NodeCredentials): Header =
    BasicAuth.fromCredentials(Credentials(User.Id(nodeCredentials.username), PlainTextSecret(nodeCredentials.password))).header


  private def withRetries[E, A](source: => Task[Either[E, A]]) =
    retryBackoffEither(
      source = source,
      maxRetries = retryConfig.maxRetries,
      firstDelay = retryConfig.initialDelay,
      backOffScaler = retryConfig.backoffScaler
    )

  private val retryConfig: RetryConfig = RetryConfig(initialDelay = 500.milliseconds, backoffScaler = 2, maxRetries = 3)

  private def httpClientConfig(poolSize: Int Refined Positive) = Config(
    connectionTimeout = positiveFiniteDuration(10, TimeUnit.SECONDS),
    requestTimeout = positiveFiniteDuration(20, TimeUnit.SECONDS),
    connectionPoolSize = poolSize,
    validate = false
  )

}

object AuditRemoteClusterHealthcheck {

  given Show[AuditClusterNode] = Show.show(_.toUrl.show)

  final case class RetryConfig(initialDelay: FiniteDuration, backoffScaler: Int, maxRetries: Int)

  final case class AuditNodeInfo(node: AuditClusterNode, info: ClusterInfoResponse)

  final case class ClusterInfoResponse(nodeName: String,
                                       clusterName: String,
                                       clusterUuid: String)

  object ClusterInfoResponse {
    given Decoder[ClusterInfoResponse] = Decoder.forProduct3("name", "cluster_name", "cluster_uuid")(
      (name: String, clusterName: String, clusterUuid: String) =>
        ClusterInfoResponse(
          nodeName = name, clusterName = clusterName, clusterUuid = clusterUuid
        )
    )
  }

  sealed trait ConnectionError

  object ConnectionError {
    final case class UnexpectedResponse(node: AuditClusterNode, message: String) extends ConnectionError

    final case class UnexpectedConnectionError(node: AuditClusterNode, cause: Throwable) extends ConnectionError

    given Show[ConnectionError] = Show.show {
      case UnexpectedResponse(node, message) => s"Unexpected response from audit node: ${node.show}. Details: $message"
      case UnexpectedConnectionError(node, cause) => s"Unexpected connection error from audit node: ${node.show}"
    }
  }
}
