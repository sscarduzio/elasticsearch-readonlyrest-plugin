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
import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.elasticsearch._types.Result
import co.elastic.clients.elasticsearch.core.CreateRequest
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest_client.RestClientTransport
import co.elastic.clients.util.ObjectBuilder
import io.lemonlabs.uri.Uri
import monix.execution.Scheduler
import org.apache.http.HttpHost
import org.apache.http.auth.{AuthScope, Credentials, UsernamePasswordCredentials}
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.client.RestClient
import tech.beshu.ror.accesscontrol.domain.AuditCluster
import tech.beshu.ror.es.AuditSinkService

import java.security.cert.X509Certificate
import java.util.function.Function
import javax.net.ssl.{SSLContext, TrustManager, X509TrustManager}
import scala.compat.java8.FutureConverters._
import scala.util.{Failure, Success}

class HighLevelClientAuditSinkService private(clients: NonEmptyList[RestClientTransport])
                                             (implicit scheduler: Scheduler)
  extends AuditSinkService
    with Logging {

  private val esClients = clients.map(new ElasticsearchAsyncClient(_))

  override def submit(indexName: String, documentId: String, jsonRecord: String): Unit = {
    esClients.toList.par.foreach { client =>
      client
        .create(
          new Function[CreateRequest.Builder[String], ObjectBuilder[CreateRequest[String]]] {
            override def apply(builder: CreateRequest.Builder[String]): ObjectBuilder[CreateRequest[String]] = {
              builder.index(indexName).id(documentId).document(jsonRecord)
            }
          }
        )
        .toScala
        .onComplete {
          case Success(response) if response.result() == Result.Created =>
          case Success(response) =>
            logger.error(s"Cannot submit audit event [index: $indexName, doc: $documentId] - response code: ${response.result().jsonValue()}")
          case Failure(ex) =>
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
    val clients = remoteCluster.uris.map(createRestClientTransport)
    new HighLevelClientAuditSinkService(clients)
  }

  private def createRestClientTransport(uri: Uri) = {
    val host = new HttpHost(
      uri.toUrl.hostOption.map(_.value).getOrElse("localhost"),
      uri.toUrl.port.getOrElse(9200),
      uri.schemeOption.getOrElse("http")
    )
    val credentials: Option[Credentials] = uri.toUrl.user.map { user =>
      new UsernamePasswordCredentials(user, uri.toUrl.password.getOrElse(""))
    }

    val restClient = RestClient
      .builder(host)
      .setHttpClientConfigCallback(
        (httpClientBuilder: HttpAsyncClientBuilder) => {
          val configurations = configureCredentials(credentials) andThen configureSsl()
          configurations apply httpClientBuilder
        }
      )
      .build()

    new RestClientTransport(restClient, new JacksonJsonpMapper())
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
