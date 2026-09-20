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
import eu.timepit.refined.api.Refined
import io.circe.Decoder
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.AuditCluster.{AuditClusterNode, NodeCredentials, RemoteAuditCluster}
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.factory.SimpleHttpClient.Config
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RefinedUtils.positiveFiniteDuration
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.utils.ScalaOps.retryBackoffEither

import java.util.concurrent.TimeUnit
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.control.NonFatal

trait AuditRemoteClusterConnectivityCheck {
  def check(cluster: RemoteAuditCluster): Task[Either[AuditRemoteClusterConnectivityCheck.Error, Unit]]
}

object AuditRemoteClusterConnectivityCheck {

  sealed trait Error { def message: String }

  object Error {
    final case class ConnectivityError(message: String) extends Error
    final case class ConfigurationError(message: String) extends Error
  }

}

final class NodesInfoBasedAuditRemoteClusterConnectivityCheck(httpClientsFactory: HttpClientsFactory)(
    using RequestId
) extends AuditRemoteClusterConnectivityCheck
    with RequestIdAwareLogging {

  import AuditRemoteClusterConnectivityCheck.Error
  import NodesInfoBasedAuditRemoteClusterConnectivityCheck.NodeCheckError.*
  import NodesInfoBasedAuditRemoteClusterConnectivityCheck.{*, given}

  override def check(cluster: RemoteAuditCluster): Task[Either[Error, Unit]] = {
    createHttpClient(cluster)
      .use { httpClient =>
        fetchNodesInfo(cluster, httpClient)
      }
      .map { auditNodesInfo =>
        validateNodesInfo(cluster, auditNodesInfo)
      }
      .timeoutTo(
        checkTimeout,
        Task.delay {
          logger.error(s"Remote audit cluster healthcheck did not complete in ${checkTimeout.show}")
          Left(
            Error.ConnectivityError(
              s"Audit cluster healthcheck failed for remote cluster ${cluster.show}. " +
                s"Details: the healthcheck did not complete in ${checkTimeout.show}"
            )
          )
        }
      )
      .recover { case NonFatal(ex) =>
        logger.error("Unexpected error while remote audit cluster healthcheck", ex)
        Left(Error.ConnectivityError("Unexpected error while remote audit cluster healthcheck"))
      }
  }

  private def createHttpClient(cluster: RemoteAuditCluster) = {
    val httpConfig = Config(
      connectionTimeout = positiveFiniteDuration(10, TimeUnit.SECONDS),
      requestTimeout = positiveFiniteDuration(20, TimeUnit.SECONDS),
      // The healthcheck asks every node at the same time. A pool smaller than the cluster makes the surplus nodes
      // wait for a free connection, and that wait is bounded by the connection timeout, so a healthy node can be
      // reported as unreachable only because the pool was busy.
      connectionPoolSize = Refined.unsafeApply(cluster.nodes.size),
      validate = false
    )
    Resource.make(Task.delay(httpClientsFactory.create(httpConfig)))(_.close())
  }

  private def fetchNodesInfo(
      cluster: RemoteAuditCluster,
      httpClient: HttpClient
  ): Task[NonEmptyList[Either[NodeCheckError, AuditNodeInfo]]] = {
    cluster.nodes.toNonEmptyList.parTraverse { node =>
      withRetries(fetchNodeInfo(cluster, httpClient, node))
    }
  }

  private def fetchNodeInfo(
      cluster: RemoteAuditCluster,
      httpClient: HttpClient,
      node: AuditClusterNode
  ): Task[Either[NodeCheckError, AuditNodeInfo]] = {
    httpClient
      .send(healthCheckRequest(node, cluster.credentials))
      .map[Either[NodeCheckError, AuditNodeInfo]] { response =>
        for {
          _ <- (response.status match {
            case 200 =>
              Right(())
            case 401 | 403 =>
              Left(RejectedCredentials(node, response.status))
            case status if TransientResponse.isTransientStatus(status) =>
              Left(TransientResponse(node, s"Temporary status code: $status for GET cluster info request"))
            case status =>
              Left(UnexpectedResponse(node, s"Unexpected status code: $status for GET cluster info request"))
          }): Either[NodeCheckError, Unit]
          responseJson <- io.circe.parser
            .parse(response.body)
            .leftMap(_ => InvalidResponsePayload(node, "Response is not a valid JSON document"))
          clusterInfo <- responseJson.as[ClusterInfoResponse].leftMap { _ =>
            InvalidResponsePayload(node, "Invalid response for GET cluster info request")
          }
        } yield AuditNodeInfo(node, clusterInfo)
      }
      .recover { case NonFatal(ex) =>
        logger.error(s"Unexpected connection error while fetching cluster info from node ${node.show}", ex)
        Left(UnexpectedConnectionError(node, ex))
      }
  }

  private def validateNodesInfo(
      cluster: RemoteAuditCluster,
      nodeInfoResults: NonEmptyList[Either[NodeCheckError, AuditNodeInfo]]
  ): Either[Error, Unit] = {
    val nodeResults: Ior[NonEmptyList[NodeCheckError], NonEmptyList[AuditNodeInfo]] =
      nodeInfoResults.reduceMap(_.toIor.bimap(NonEmptyList.one, NonEmptyList.one))
    nodeResults match {
      case Ior.Left(errors) =>
        Left(
          Error.ConnectivityError(
            s"Audit cluster healthcheck failed for remote cluster ${cluster.show}. Details: No healthy node detected in remote cluster. ${errors.map(_.show).toList.show}"
          )
        )
      case Ior.Right(infos) =>
        ensureNodesFromSameCluster(infos).leftMap { details =>
          Error.ConfigurationError(
            s"Audit cluster healthcheck failed for remote cluster ${cluster.show}. Details: $details"
          )
        }
      case Ior.Both(errors, infos) =>
        ensureNodesFromSameCluster(infos) match {
          case Right(()) =>
            logger.warn(
              s"Some audit cluster nodes are unreachable, but auditing will proceed using the remaining nodes. " +
                s"Details: ${errors.map(_.show).toList.show}"
            )
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
        s"One audit output can use only nodes from one cluster. " +
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

  /**
   * Retries only the transient failures: a connection error, or a status code which says that the node is not ready
   * yet. A node which answers something else gives the same answer to every attempt, so retrying it only makes the
   * healthcheck longer.
   */
  private def withRetries(
      source: => Task[Either[NodeCheckError, AuditNodeInfo]]
  ): Task[Either[NodeCheckError, AuditNodeInfo]] =
    retryBackoffEither(
      source = source.map {
        case Left(error) if error.isTransient => Left(error)
        case finalResult                      => Right(finalResult)
      },
      maxRetries = retryConfig.maxRetries,
      firstDelay = retryConfig.initialDelay,
      backOffScaler = retryConfig.backoffScaler
    ).map(_.flatten)

  private val retryConfig: RetryConfig = RetryConfig(initialDelay = 500.milliseconds, backoffScaler = 2, maxRetries = 3)

  private val checkTimeout: FiniteDuration = 30.seconds

}

final class SharedAuditRemoteClusterConnectivityCheck(underlying: AuditRemoteClusterConnectivityCheck)
    extends AuditRemoteClusterConnectivityCheck {

  private val results =
    TrieMap.empty[RemoteAuditCluster, Task[Either[AuditRemoteClusterConnectivityCheck.Error, Unit]]]

  override def check(cluster: RemoteAuditCluster): Task[Either[AuditRemoteClusterConnectivityCheck.Error, Unit]] =
    results.getOrElseUpdate(cluster, underlying.check(cluster).memoize)

}

object NodesInfoBasedAuditRemoteClusterConnectivityCheck {

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

  /**
   * Every case says that this node did not confirm the connectivity. None of them proves that the audit output is
   * misconfigured: a node can reject the healthcheck credentials and still accept the audit writes, and a body which
   * is not an ES response can come from a proxy in front of a healthy node. Only a check which compares the answers
   * of several nodes can find a configuration error, so all these cases are connectivity problems.
   */
  private sealed trait NodeCheckError

  private object NodeCheckError {

    final case class UnexpectedResponse(node: AuditClusterNode, message: String) extends NodeCheckError
    final case class TransientResponse(node: AuditClusterNode, message: String) extends NodeCheckError

    object TransientResponse {

      def isTransientStatus(status: Int): Boolean = status match {
        case 408 | 429 | 502 | 503 | 504 => true
        case _                           => false
      }

    }

    final case class UnexpectedConnectionError(node: AuditClusterNode, cause: Throwable) extends NodeCheckError
    final case class RejectedCredentials(node: AuditClusterNode, statusCode: Int) extends NodeCheckError
    final case class InvalidResponsePayload(node: AuditClusterNode, message: String) extends NodeCheckError

    extension (error: NodeCheckError) {

      def isTransient: Boolean = {
        error match {
          case _: UnexpectedConnectionError => true
          case _: TransientResponse         => true
          case _: UnexpectedResponse        => false
          case _: RejectedCredentials       => false
          case _: InvalidResponsePayload    => false
        }
      }

    }

    given Show[NodeCheckError] = Show.show {
      case UnexpectedResponse(node, message) => s"Unexpected response from audit node: ${node.show}. Details: $message"
      case TransientResponse(node, message)  => s"Audit node is not ready: ${node.show}. Details: $message"
      case InvalidResponsePayload(node, message) =>
        s"Unexpected response from audit node: ${node.show}. Details: $message"
      case RejectedCredentials(node, statusCode) =>
        s"Audit node rejected the credentials: ${node.show}. Details: status code $statusCode"
      case UnexpectedConnectionError(node, cause) =>
        s"Unexpected connection error from audit node: ${node.show}. Details: ${causeDetails(cause)}"
    }

    private def causeDetails(cause: Throwable): String = {
      Option(cause.getMessage) match {
        case Some(message) => s"${cause.getClass.getSimpleName}: $message"
        case None          => cause.getClass.getSimpleName
      }
    }

  }

}
