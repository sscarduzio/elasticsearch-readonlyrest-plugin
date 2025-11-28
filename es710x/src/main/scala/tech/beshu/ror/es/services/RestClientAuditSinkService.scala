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
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import tech.beshu.ror.utils.RequestIdAwareLogging
import org.elasticsearch.client.*
import org.elasticsearch.client.RestClient.FailureListener
import tech.beshu.ror.accesscontrol.audit.sink.AuditDataStreamCreator
import tech.beshu.ror.accesscontrol.domain.AuditCluster.ClusterMode
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, DataStreamName, IndexName, RequestId}
import tech.beshu.ror.es.{DataStreamBasedAuditSinkService, IndexBasedAuditSinkService}
import tech.beshu.ror.implicits.{requestIdShow, toShow}

import java.security.cert.X509Certificate
import javax.net.ssl.{SSLContext, TrustManager, X509TrustManager}
import scala.collection.parallel.CollectionConverters.*

final class RestClientAuditSinkService private(clients: NonEmptyList[RestClient])
  extends IndexBasedAuditSinkService
    with DataStreamBasedAuditSinkService
    with RequestIdAwareLogging {

  override def submit(indexName: IndexName.Full, documentId: String, jsonRecord: String)
                     (implicit requestId: RequestId): Unit = {
    submitDocument(indexName.name.value, documentId, jsonRecord)
  }

  override def submit(dataStreamName: DataStreamName.Full, documentId: String, jsonRecord: String)
                     (implicit requestId: RequestId): Unit = {
    submitDocument(dataStreamName.value.value, documentId, jsonRecord)
  }

  override def close(): Unit = {
    clients.toList.par.foreach(_.close())
  }

  private def submitDocument(indexName: String, documentId: String, jsonRecord: String)(implicit requestId: RequestId): Unit = {
    clients.toList.par.foreach { client =>
      client
        .performRequestAsync(
          createRequest(indexName, documentId, jsonRecord),
          createResponseListener(indexName, documentId)
        )
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
        response.getStatusLine.getStatusCode / 100 match {
          case 2 => // 2xx
            logger.debug(s"[${requestId.show}] Audit event handled by node ${response.getHost.getHostName}:${response.getHost.getPort}")
          case _ =>
            noRequestIdLogger.error(s"[${requestId.show}] Cannot submit audit event [index: $indexName, doc: $documentId] - response code: ${response.getStatusLine.getStatusCode}")
        }
      }

      override def onFailure(ex: Exception): Unit = {
        noRequestIdLogger.error(s"[${requestId.show}] Cannot submit audit event [index: $indexName, doc: $documentId]", ex)
      }
    }

  override val dataStreamCreator: AuditDataStreamCreator = new AuditDataStreamCreator(clients.map(new RestClientDataStreamService(_)))
}

object RestClientAuditSinkService extends RequestIdAwareLogging {

  def create(remoteCluster: AuditCluster.RemoteAuditCluster): RestClientAuditSinkService = {
    remoteCluster.mode match {
      case ClusterMode.RoundRobin =>
        val clients = NonEmptyList.one(createRestClient(remoteCluster))
        new RestClientAuditSinkService(clients)
    }
  }

  private def createRestClient(remoteCluster: AuditCluster.RemoteAuditCluster) = {
    val hosts = remoteCluster.nodes.map { node =>
      new HttpHost(node.hostname, node.port, node.scheme)
    }

    val credentials =
      remoteCluster
        .credentials
        .map(c => new UsernamePasswordCredentials(c.username.value, c.password.value))

    RestClient
      .builder(hosts.toSeq: _*)
      .setHttpClientConfigCallback(
        (httpClientBuilder: HttpAsyncClientBuilder) => {
          val configurations = configureCredentials(credentials) andThen configureSsl()
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
}
