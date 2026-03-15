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
package tech.beshu.ror.es.services

import cats.data.NonEmptyList
import org.apache.http.HttpHost
import org.apache.http.auth.{AuthScope, Credentials, UsernamePasswordCredentials}
import org.apache.http.client.config.RequestConfig
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.elasticsearch.client.*
import org.elasticsearch.client.RestClient.FailureListener
import tech.beshu.ror.accesscontrol.domain.AuditCluster.{AuditClusterNode, ClusterMode}
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, IndexName, RequestId}
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.security.cert.X509Certificate
import java.util.concurrent.Semaphore
import javax.net.ssl.{SSLContext, TrustManager, X509TrustManager}

final class RestClientAuditSinkService private(client: MultiNodeRestClient, inFlightRequestSemaphore: Semaphore)
  extends IndexBasedAuditSinkService
    with RequestIdAwareLogging {

  override def submit(indexName: IndexName.Full, documentId: String, jsonRecord: String)
                     (implicit requestId: RequestId): Unit = {
    submitDocument(indexName.name.value, documentId, jsonRecord)
  }

  override def close(): Unit = {
    client.close()
  }

  private def submitDocument(indexName: String, documentId: String, jsonRecord: String)(implicit requestId: RequestId): Unit = {
    if (inFlightRequestSemaphore.tryAcquire()) {
      client
        .performRequestAsync(
          createRequest(indexName, documentId, jsonRecord),
          createResponseListener(indexName, documentId)
        )
    } else {
      logger.error(s"Cannot submit audit event [index: $indexName, doc: $documentId] — too many in-flight requests")
    }
  }

  private def createRequest(indexName: String, documentId: String, jsonBody: String) = {
    val request = new Request("PUT", s"/$indexName/_doc/$documentId")
    request.addParameter("op_type", "create")
    request.setJsonEntity(jsonBody)
    request
  }

  private def createResponseListener(indexName: String,
                                     documentId: String)(implicit requestId: RequestId) =
    new ResponseListener() {
      override def onSuccess(response: Response): Unit = {
        try {
          response.getStatusLine.getStatusCode / 100 match {
            case 2 => // 2xx
              logger.debug(s"Audit event handled by node ${response.getHost.getHostName}:${response.getHost.getPort}")
            case _ =>
              logger.error(s"Cannot submit audit event [index: $indexName, doc: $documentId] - response code: ${response.getStatusLine.getStatusCode}")
          }
        } finally {
          inFlightRequestSemaphore.release()
        }
      }

      override def onFailure(ex: Exception): Unit = {
        try {
          logger.error(s"Cannot submit audit event [index: $indexName, doc: $documentId]", ex)
        } finally {
          inFlightRequestSemaphore.release()
        }
      }
    }
}

object RestClientAuditSinkService extends RequestIdAwareLogging {

  def create(remoteCluster: AuditCluster.RemoteAuditCluster): RestClientAuditSinkService = {
    val hosts = remoteCluster.nodes.toNonEmptyList.map(toHttpHost)
    remoteCluster.mode match {
      case ClusterMode.RoundRobin =>
        val restClient = createRestClient(remoteCluster, hosts)
        val clusterAwareClient = new RoundRobinClient(restClient)
        createService(remoteCluster, clusterAwareClient)
      case ClusterMode.Failover =>
        val clientsPerNode = hosts.map(host => createRestClient(remoteCluster, NonEmptyList.one(host)))
        val clusterAwareClient = FailoverClient.create(clientsPerNode)
        createService(remoteCluster, clusterAwareClient)
    }
  }

  private def createService(remoteCluster: AuditCluster.RemoteAuditCluster,
                            client: MultiNodeRestClient) = {
    new RestClientAuditSinkService(
      client = client,
      inFlightRequestSemaphore = new Semaphore(remoteCluster.maxInflightRequests),
    )
  }

  private def createRestClient(remoteCluster: AuditCluster.RemoteAuditCluster, hosts: NonEmptyList[HttpHost]): RestClient = {
    val credentials =
      remoteCluster
        .credentials
        .map(c => new UsernamePasswordCredentials(c.username.value, c.password.value))

    RestClient
      .builder(hosts.toList: _*)
      .setHttpClientConfigCallback(
        (httpClientBuilder: HttpAsyncClientBuilder) => {
          val configurations = configureRequestConfig(remoteCluster) andThen configureCredentials(credentials) andThen configureSsl()
          configurations apply httpClientBuilder
        }
      )
      .setFailureListener(
        new FailureListener {
          override def onFailure(node: Node): Unit = {
            noRequestIdLogger.debug(
              s"[AUDIT] Node marked dead: ${node.getHost.getSchemeName}://${node.getHost.getHostName}:${node.getHost.getPort}. The client will attempt failover.",
            )
          }
        }
      )
      .build()
  }

  private def configureRequestConfig(cluster: AuditCluster.RemoteAuditCluster): HttpAsyncClientBuilder => HttpAsyncClientBuilder = (httpClientBuilder: HttpAsyncClientBuilder) => {
    httpClientBuilder
      .setDefaultRequestConfig(
        RequestConfig.custom()
          .setConnectTimeout(cluster.connectionTimeout.toMillis.toInt)
          .setConnectionRequestTimeout(cluster.connectionRequestTimeout.toMillis.toInt)
          .setSocketTimeout(cluster.requestTimeout.toMillis.toInt)
          .build()
      )
  }

  private def configureCredentials(credentials: Option[Credentials]): HttpAsyncClientBuilder => HttpAsyncClientBuilder = (httpClientBuilder: HttpAsyncClientBuilder) => {
    credentials match {
      case Some(c) =>
        httpClientBuilder
          .disableAuthCaching()
          .setDefaultCredentialsProvider {
            val credentialsProvider = new BasicCredentialsProvider
            credentialsProvider.setCredentials(AuthScope.ANY, c)
            credentialsProvider
          }
      case None =>
        httpClientBuilder
    }
  }

  private def configureSsl(): HttpAsyncClientBuilder => HttpAsyncClientBuilder = (httpClientBuilder: HttpAsyncClientBuilder) => {
    val trustAllCerts = createTrustAllManager()
    val sslContext = SSLContext.getInstance("TLSv1.2")
    sslContext.init(null, Array(trustAllCerts), null)
    httpClientBuilder
      .setSSLContext(sslContext)
      .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
  }

  private def createTrustAllManager(): TrustManager = new X509TrustManager() {
    override def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = ()
    override def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = ()
    override def getAcceptedIssuers: Array[X509Certificate] = null
  }

  private def toHttpHost(node: AuditClusterNode) = {
    new HttpHost(node.hostname, node.port, node.scheme)
  }
}
