/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.services

import monix.execution.Scheduler
import org.apache.http.HttpHost
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.client.{RestClient, RestHighLevelClient}
import org.elasticsearch.common.xcontent.XContentType
import tech.beshu.ror.accesscontrol.domain.AuditCluster
import tech.beshu.ror.es.AuditSinkService
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter

import java.security.cert.X509Certificate
import javax.net.ssl.{SSLContext, TrustManager, X509TrustManager}

class ProxyAuditSinkService private(client: RestHighLevelClientAdapter)
                                   (implicit scheduler: Scheduler)
  extends AuditSinkService
    with Logging {

  override def submit(indexName: String, documentId: String, jsonRecord: String): Unit = {
    client
      .getIndex(new IndexRequest(indexName).id(documentId).source(jsonRecord, XContentType.JSON))
      .runAsync {
        case Right(resp) if resp.status().getStatus / 100 == 2 =>
        case Right(resp) =>
          logger.error(s"Cannot submit audit event [index: $indexName, doc: $documentId] - response code: ${resp.status().getStatus}")
        case Left(ex) =>
          logger.error(s"Cannot submit audit event [index: $indexName, doc: $documentId]", ex)
      }
  }

  override def close(): Unit = {
    client.close()
  }
}

object ProxyAuditSinkService {

  def create(localClusterClient: RestHighLevelClientAdapter)
            (auditCluster: AuditCluster)
            (implicit scheduler: Scheduler): ProxyAuditSinkService = {
    val clientAdapter = auditCluster match {
      case AuditCluster.LocalAuditCluster =>
        localClusterClient
      case remote: AuditCluster.RemoteAuditCluster =>
        new RestHighLevelClientAdapter(createEsHighLevelClient(remote))
    }
    new ProxyAuditSinkService(clientAdapter)
  }

  private def createEsHighLevelClient(remoteAuditCluster: AuditCluster.RemoteAuditCluster) = {
    val hosts = remoteAuditCluster.uris.map { uri =>
      new HttpHost(uri.host, uri.port.getOrElse(9200), uri.scheme)
    }.toList

    new RestHighLevelClient(
      RestClient
        .builder(hosts: _*)
        .setHttpClientConfigCallback(
          (httpClientBuilder: HttpAsyncClientBuilder) => {
            val trustAllCerts = createTrustAllManager()
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, Array(trustAllCerts), null)
            httpClientBuilder
              .setSSLContext(sslContext)
              .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
          }
        )
    )
  }

  private def createTrustAllManager(): TrustManager = new X509TrustManager() {
    override def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = ()
    override def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = ()
    override def getAcceptedIssuers: Array[X509Certificate] = null
  }
}
