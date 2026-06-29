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
import cats.data.{Ior, NonEmptyList}
import cats.effect.Resource
import cats.implicits.*
import io.circe.Decoder
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.AuditCluster.{AuditClusterNode, NodeCredentials, RemoteAuditCluster}
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.factory.SimpleHttpClient.Config
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RefinedUtils.{positiveFiniteDuration, positiveInt}
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.utils.ScalaOps.retryBackoffEither

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.control.NonFatal

final class AuditRemoteClusterConnectivityCheck(httpClientsFactory: HttpClientsFactory) extends RequestIdAwareLogging {

  import AuditRemoteClusterConnectivityCheck.*
  import AuditRemoteClusterConnectivityCheck.given

  def check(cluster: RemoteAuditCluster): Task[Either[Error, Unit]] = {
    given RequestId = RequestId(UUID.randomUUID().toString)

    createHttpClient()
      .use { httpClient =>
        fetchNodesInfo(cluster, httpClient)
      }
      .map { auditNodesInfo =>
        validateNodesInfo(cluster, auditNodesInfo)
      }
      .recover { case NonFatal(ex) =>
        logger.error("Unexpected error while remote audit cluster healthcheck", ex)
        Left(Error.ConnectivityError("Unexpected error while remote audit cluster healthcheck"))
      }
  }

  private def createHttpClient() = {
    val httpConfig = Config(
      connectionTimeout = positiveFiniteDuration(10, TimeUnit.SECONDS),
      requestTimeout = positiveFiniteDuration(20, TimeUnit.SECONDS),
      connectionPoolSize = positiveInt(5),
      validate = false
    )
    Resource.make(Task.delay(httpClientsFactory.create(httpConfig)))(_.close())
  }

  private def fetchNodesInfo(cluster: RemoteAuditCluster, httpClient: HttpClient)(
      using RequestId
  ): Task[NonEmptyList[Either[ConnectionError, AuditNodeInfo]]] = {
    cluster.nodes.toNonEmptyList.parTraverse { node =>
      withRetries(fetchNodeInfo(cluster, httpClient, node))
    }
  }

  private def fetchNodeInfo(cluster: RemoteAuditCluster, httpClient: HttpClient, node: AuditClusterNode)(
      using RequestId
  ): Task[Either[ConnectionError, AuditNodeInfo]] = {
    httpClient
      .send(healthCheckRequest(node, cluster.credentials))
      .map[Either[ConnectionError, AuditNodeInfo]] { response =>
        for {
          _ <- Either.cond(
            response.status == 200,
            (),
            ConnectionError
              .UnexpectedResponse(node, s"Unexpected status code: ${response.status} for GET cluster info request")
          )
          responseJson <- io.circe.parser
            .parse(response.body)
            .leftMap(_ => ConnectionError.UnexpectedResponse(node, "Response is not a valid JSON document"))
          clusterInfo <- responseJson.as[ClusterInfoResponse].leftMap { _ =>
            ConnectionError.UnexpectedResponse(node, "Invalid response for GET cluster info request")
          }
        } yield AuditNodeInfo(node, clusterInfo)
      }
      .recover { case NonFatal(ex) =>
        logger.error(s"Unexpected connection error while fetching cluster info from node ${node.show}", ex)
        Left(ConnectionError.UnexpectedConnectionError(node, ex))
      }
  }

  private def validateNodesInfo(
      cluster: RemoteAuditCluster,
      nodeInfoResults: NonEmptyList[Either[ConnectionError, AuditNodeInfo]]
  )(
      using RequestId
  ): Either[Error, Unit] = {
    val nodeResults: Ior[NonEmptyList[ConnectionError], NonEmptyList[AuditNodeInfo]] =
      nodeInfoResults.reduceMap(_.toIor.bimap(NonEmptyList.one, NonEmptyList.one))
    nodeResults match {
      case Ior.Left(errors) =>
        Left(
          Error.ConnectivityError(
            s"Audit cluster healthcheck failed for remote cluster ${cluster.show}. Details: No health node detected in remote cluster. ${errors.map(_.show).toList.show}"
          )
        )
      case Ior.Right(infos) =>
        ensureNodesFromSameCluster(infos).leftMap { details =>
          Error.ConfigurationError(
            s"Audit cluster healthcheck failed for remote cluster ${cluster.show}. Details: $details"
          )
        }
      case Ior.Both(_, infos) =>
        ensureNodesFromSameCluster(infos) match {
          case Right(()) =>
            logger.warn("Some audit cluster nodes are unreachable, but auditing will proceed using the remaining nodes")
            Right(())
          case Left(details) =>
            Left(
              Error.ConfigurationError(
                s"Audit cluster healthcheck failed for remote cluster ${cluster.show}. Details: $details"
              )
            )
        }
    }
  }

  private def ensureNodesFromSameCluster(infos: NonEmptyList[AuditNodeInfo]): Either[String, Unit] = {
    val nodesByClusterUuid = infos.groupBy(_.info.clusterUuid)
    Either.cond(
      nodesByClusterUuid.size == 1,
      (),
      s"Configured remote cluster for audit contains ES nodes belonging to different ES clusters " +
        s"(found cluster UUIDs: ${nodesByClusterUuid.keys.mkString("[", ", ", "]")}). " +
        s"One audit sink can use only nodes from one cluster. " +
        s"See https://docs.readonlyrest.com/elasticsearch/audit#custom-audit-cluster"
    )
  }

  private def healthCheckRequest(node: AuditClusterNode, credentials: Option[NodeCredentials]): HttpClient.Request = {
    HttpClient.Request(
      HttpClient.Method.Get,
      node.toUrl,
      headers = credentials.map { c =>
        val header = toBasicAuthHeader(c)
        (header.name.value.value, header.value.value)
      }.toMap
    )
  }

  private def toBasicAuthHeader(nodeCredentials: NodeCredentials): Header =
    BasicAuth
      .fromCredentials(Credentials(User.Id(nodeCredentials.username), PlainTextSecret(nodeCredentials.password)))
      .header

  private def withRetries[E, A](source: => Task[Either[E, A]]) =
    retryBackoffEither(
      source = source,
      maxRetries = retryConfig.maxRetries,
      firstDelay = retryConfig.initialDelay,
      backOffScaler = retryConfig.backoffScaler
    )

  private val retryConfig: RetryConfig = RetryConfig(initialDelay = 500.milliseconds, backoffScaler = 2, maxRetries = 3)

}

object AuditRemoteClusterConnectivityCheck {

  sealed trait Error { def message: String }

  object Error {
    final case class ConnectivityError(message: String) extends Error
    final case class ConfigurationError(message: String) extends Error
  }

  private final case class RetryConfig(initialDelay: FiniteDuration, backoffScaler: Int, maxRetries: Int)

  private final case class AuditNodeInfo(node: AuditClusterNode, info: ClusterInfoResponse)

  private final case class ClusterInfoResponse(nodeName: String, clusterName: String, clusterUuid: String)

  private object ClusterInfoResponse {

    given Decoder[ClusterInfoResponse] = Decoder.forProduct3("name", "cluster_name", "cluster_uuid")(
      (name: String, clusterName: String, clusterUuid: String) =>
        ClusterInfoResponse(
          nodeName = name,
          clusterName = clusterName,
          clusterUuid = clusterUuid
        )
    )

  }

  private sealed trait ConnectionError

  private object ConnectionError {
    final case class UnexpectedResponse(node: AuditClusterNode, message: String) extends ConnectionError

    final case class UnexpectedConnectionError(node: AuditClusterNode, cause: Throwable) extends ConnectionError

    given Show[ConnectionError] = Show.show {
      case UnexpectedResponse(node, message) => s"Unexpected response from audit node: ${node.show}. Details: $message"
      case UnexpectedConnectionError(node, cause) => s"Unexpected connection error from audit node: ${node.show}"
    }

  }

}
