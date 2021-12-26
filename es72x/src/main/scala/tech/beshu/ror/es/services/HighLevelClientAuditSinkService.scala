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

import monix.execution.Scheduler
import org.apache.http.HttpHost
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.index.{IndexRequest, IndexResponse}
import org.elasticsearch.client.{RequestOptions, RestClient, RestHighLevelClient}
import org.elasticsearch.common.xcontent.XContentType
import tech.beshu.ror.accesscontrol.domain.AuditCluster
import tech.beshu.ror.es.AuditSinkService
import tech.beshu.ror.es.utils.GenericResponseListener

import java.security.cert.X509Certificate
import javax.net.ssl.{SSLContext, TrustManager, X509TrustManager}

class HighLevelClientAuditSinkService private(client: RestHighLevelClient)
                                             (implicit scheduler: Scheduler)
  extends AuditSinkService
    with Logging {

  override def submit(indexName: String, documentId: String, jsonRecord: String): Unit = {
    val request = new IndexRequest(indexName).id(documentId).source(jsonRecord, XContentType.JSON)
    val options = RequestOptions.DEFAULT
    val listener = new GenericResponseListener[IndexResponse]

    client.indexAsync(request, options, listener)

    listener.result
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

object HighLevelClientAuditSinkService {

  def create(remoteCluster: AuditCluster.RemoteAuditCluster)
            (implicit scheduler: Scheduler): HighLevelClientAuditSinkService = {
    val highLevelClient = createEsHighLevelClient(remoteCluster)
    new HighLevelClientAuditSinkService(highLevelClient)
  }

  private def createEsHighLevelClient(auditCluster: AuditCluster.RemoteAuditCluster) = {
    val hosts = auditCluster.uris.map { uri =>
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
