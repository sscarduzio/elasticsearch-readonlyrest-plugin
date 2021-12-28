/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.services

import java.security.cert.X509Certificate

import cats.data.NonEmptyList
import io.lemonlabs.uri.Uri
import javax.net.ssl.{SSLContext, TrustManager, X509TrustManager}
import monix.execution.Scheduler
import org.apache.http.HttpHost
import org.apache.http.auth.{AuthScope, Credentials, UsernamePasswordCredentials}
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.client.{RestClient, RestHighLevelClient}
import org.elasticsearch.common.xcontent.XContentType
import tech.beshu.ror.accesscontrol.domain.AuditCluster
import tech.beshu.ror.es.AuditSinkService
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter

class ProxyAuditSinkService private(clients: NonEmptyList[RestHighLevelClientAdapter])
                                   (implicit scheduler: Scheduler)
  extends AuditSinkService
    with Logging {

  override def submit(indexName: String, documentId: String, jsonRecord: String): Unit = {
    clients.toList.par.foreach { client =>
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
  }

  override def close(): Unit = {
    clients.toList.par.foreach(_.close())
  }
}

object ProxyAuditSinkService {

  def create(localClusterClient: RestHighLevelClientAdapter)
            (auditCluster: AuditCluster)
            (implicit scheduler: Scheduler): ProxyAuditSinkService = {
    val clientAdapters = auditCluster match {
      case AuditCluster.LocalAuditCluster =>
        NonEmptyList.one(localClusterClient)
      case remote: AuditCluster.RemoteAuditCluster =>
        remote.uris
          .map(createEsHighLevelClient)
          .map(new RestHighLevelClientAdapter(_))
    }
    new ProxyAuditSinkService(clientAdapters)
  }

  private def createEsHighLevelClient(uri: Uri) = {
    val host = new HttpHost(
      uri.toUrl.hostOption.map(_.value).getOrElse("localhost"),
      uri.toUrl.port.getOrElse(9200),
      uri.schemeOption.getOrElse("http")
    )
    val credentials = uri.toUrl.user.map { user =>
      new UsernamePasswordCredentials(user, uri.toUrl.password.getOrElse(""))
    }

    new RestHighLevelClient(
      RestClient
        .builder(host)
        .setHttpClientConfigCallback(
          (httpClientBuilder: HttpAsyncClientBuilder) => {
            val configurations = configureCredentials(credentials) andThen configureSsl()
            configurations apply httpClientBuilder
          }
        )
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
    val sslContext = SSLContext.getInstance("TLS")
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
