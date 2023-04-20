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
import io.lemonlabs.uri.Uri
import monix.execution.Scheduler
import org.apache.http.HttpHost
import org.apache.http.auth.{AuthScope, Credentials, UsernamePasswordCredentials}
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.client.{Request, Response, ResponseListener, RestClient}
import tech.beshu.ror.accesscontrol.domain.AuditCluster
import tech.beshu.ror.es.AuditSinkService

import java.security.cert.X509Certificate
import javax.net.ssl.{SSLContext, TrustManager, X509TrustManager}
import scala.collection.parallel.CollectionConverters._

class RestClientAuditSinkService private(clients: NonEmptyList[RestClient])
                                        (implicit scheduler: Scheduler)
  extends AuditSinkService
    with Logging {

  override def submit(indexName: String, documentId: String, jsonRecord: String): Unit = {
    clients.toList.par.foreach { client =>
      client
        .performRequestAsync(
          createRequest(indexName, documentId, jsonRecord),
          createResponseListener(indexName, documentId)
        )
    }
  }

  override def close(): Unit = {
    clients.toList.par.foreach(_.close())
  }

  private def createRequest(indexName: String, documentId: String, jsonBody: String) = {
    val request = new Request("PUT", s"/$indexName/_doc/$documentId")
    request.setJsonEntity(jsonBody)
    request
  }

  private def createResponseListener(indexName: String,
                                     documentId: String) =
    new ResponseListener() {
      override def onSuccess(response: Response): Unit = {
        response.getStatusLine.getStatusCode / 100 match {
          case 2 => // 2xx
          case _ =>
            logger.error(s"Cannot submit audit event [index: $indexName, doc: $documentId] - response code: ${response.getStatusLine.getStatusCode}")
        }
      }

      override def onFailure(ex: Exception): Unit = {
        logger.error(s"Cannot submit audit event [index: $indexName, doc: $documentId]", ex)
      }
    }
}

object RestClientAuditSinkService {

  def create(remoteCluster: AuditCluster.RemoteAuditCluster)
            (implicit scheduler: Scheduler): RestClientAuditSinkService = {
    val clients = remoteCluster.uris.map(createRestClient)
    new RestClientAuditSinkService(clients)
  }

  private def createRestClient(uri: Uri) = {
    val host = new HttpHost(
      uri.toUrl.hostOption.map(_.value).getOrElse("localhost"),
      uri.toUrl.port.getOrElse(9200),
      uri.schemeOption.getOrElse("http")
    )
    val credentials: Option[Credentials] = uri.toUrl.user.map { user =>
      new UsernamePasswordCredentials(user, uri.toUrl.password.getOrElse(""))
    }

    RestClient
      .builder(host)
      .setHttpClientConfigCallback(
        (httpClientBuilder: HttpAsyncClientBuilder) => {
          val configurations = configureCredentials(credentials) andThen configureSsl()
          configurations apply httpClientBuilder
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
