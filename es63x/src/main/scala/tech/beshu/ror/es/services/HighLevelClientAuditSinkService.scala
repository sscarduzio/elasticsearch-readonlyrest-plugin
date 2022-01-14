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
import org.elasticsearch.action.index.{IndexRequest, IndexResponse}
import org.elasticsearch.client.{RestClient, RestHighLevelClient}
import org.elasticsearch.common.xcontent.XContentType
import tech.beshu.ror.accesscontrol.domain.AuditCluster
import tech.beshu.ror.es.AuditSinkService
import tech.beshu.ror.es.utils.GenericResponseListener

class HighLevelClientAuditSinkService private(clients: NonEmptyList[RestHighLevelClient])
                                             (implicit scheduler: Scheduler)
  extends AuditSinkService
    with Logging {

  override def submit(indexName: String, documentId: String, jsonRecord: String): Unit = {
    clients.toList.par.foreach { client =>
      val request = new IndexRequest(indexName, "ror_audit_evt", documentId).source(jsonRecord, XContentType.JSON)
      val listener = new GenericResponseListener[IndexResponse]

      client.indexAsync(request, listener)

      listener.result
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

object HighLevelClientAuditSinkService {

  def create(remoteCluster: AuditCluster.RemoteAuditCluster)
            (implicit scheduler: Scheduler): HighLevelClientAuditSinkService = {
    val highLevelClients = remoteCluster.uris.map(createEsHighLevelClient)
    new HighLevelClientAuditSinkService(highLevelClients)
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
