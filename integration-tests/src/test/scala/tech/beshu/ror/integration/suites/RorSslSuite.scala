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
package tech.beshu.ror.integration.suites

import better.files.File
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.AbstractHttpEntity
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, PluginTestSupport}
import tech.beshu.ror.utils.containers.*
import tech.beshu.ror.utils.containers.EsClusterSettings.positiveInt
import tech.beshu.ror.utils.containers.SecurityType.RorSecurity
import tech.beshu.ror.utils.containers.images.ReadonlyRestPlugin.Config.{Attributes, InternodeSsl, RestSsl}
import tech.beshu.ror.utils.containers.images.domain.{Enabled, SourceFile}
import tech.beshu.ror.utils.elasticsearch.{CatManager, DocumentManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.OsUtils.ignoreOnWindows
import tech.beshu.ror.utils.misc.{CustomScalaTestMatchers, Version}

import java.io.{ByteArrayInputStream, InputStream, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*

class RorSslSuite
    extends AnyWordSpec
    with BaseEsClusterIntegrationTest
    with PluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with SingleClientSupport
    with BeforeAndAfterAll
    with CustomScalaTestMatchers {

  override implicit val rorSettingsFileName: String = "/fips_ssl/readonlyrest.yml"

  override def clusterContainer: EsClusterContainer = generalClusterContainer

  override def targetEs: EsContainer = generalClusterContainer.nodes.head

  lazy val generalClusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings.create(
      clusterName = "fips_cluster",
      numberOfInstances = positiveInt(2),
      securityType = RorSecurity(
        Attributes.default.copy(
          rorSettingsFileName = rorSettingsFileName,
          restSsl = Enabled.Yes(RestSsl.RorFips(SourceFile.RorFile)),
          internodeSsl = Enabled.Yes(InternodeSsl.RorFips(SourceFile.RorFile))
        )
      )
    )
  )

  private lazy val rorClusterAdminStateManager = new CatManager(clients.last.adminClient, esVersion = esVersionUsed)
  private lazy val adminDocumentManager = new DocumentManager(adminClient, esVersionUsed)

  private val uploadChunkSize = 8 * 1024
  private val pauseBetweenChunks = 50.millis
  private val responseTimeout = 20.seconds

  // todo: The ES with FIPS does not start correctly when running tests on Windows with ES version lower than 8.18.
  //       It needs to be checked further. It is an issue with file and thread operation permissions.
  ignoreOnWindows {
    "Health check" should {
      "be successful" when {
        "internode ssl is enabled" in {
          val response = rorClusterAdminStateManager.healthCheck()

          response should have statusCode 200
        }
      }
    }
    "A bulk request" should {
      "be handled when the body arrives in one read" in {
        val result = adminDocumentManager.bulk(bulkFileWith(index = "small_bulk_test", numberOfDocuments = 10))

        result should have statusCode 200
        result.responseJson("errors").bool should be(false)
      }
      "be handled when the body is large" in {
        val result = adminDocumentManager.bulk(bulkFileWith(index = "large_bulk_test", numberOfDocuments = 2000))

        result should have statusCode 200
        result.responseJson("errors").bool should be(false)
      }
      "be handled when the body arrives slowly" in {
        // Size alone does not stall the request. The stall needs a socket read that decodes no message,
        // which happens when the upload is slow enough that the server reads part of a TLS record. The ES
        // HTTP pipeline must then ask for a new read, or the request hangs with its remaining bytes in the
        // socket buffer. Lower `pauseBetweenChunks` only together with a check that this test still fails
        // against a build without the read-demand fix.
        val (statusCode, body) = sendBulkSlowly(index = "throttled_bulk_test", numberOfDocuments = 2000)

        statusCode should be(200)
        body should include(""""errors":false""")
      }
    }
  }

  private def sendBulkSlowly(index: String, numberOfDocuments: Int) = {
    val request = new HttpPost(adminClient.from("_bulk"))
    request.addHeader("Content-Type", "application/x-ndjson")
    request.setConfig(
      RequestConfig
        .custom()
        .setExpectContinueEnabled(true)
        .setConnectTimeout(responseTimeout.toMillis.toInt)
        .setConnectionRequestTimeout(responseTimeout.toMillis.toInt)
        .setSocketTimeout(responseTimeout.toMillis.toInt)
        .build()
    )
    request.setEntity(new ThrottledEntity(bulkBodyWith(index, numberOfDocuments)))
    adminClient.handle(request) { response =>
      (response.getStatusLine.getStatusCode, RestClient.bodyFrom(response))
    }
  }

  private def bulkFileWith(index: String, numberOfDocuments: Int): File = {
    File
      .newTemporaryFile(prefix = "ror-large-bulk-", suffix = ".ndjson")
      .deleteOnExit()
      .printLines(bulkLinesFor(index, numberOfDocuments))
  }

  private def bulkBodyWith(index: String, numberOfDocuments: Int): Array[Byte] = {
    bulkLinesFor(index, numberOfDocuments).mkString("", "\n", "\n").getBytes(UTF_8)
  }

  private def bulkLinesFor(index: String, numberOfDocuments: Int): Iterator[String] = {
    val payload = "x" * 1024
    (1 to numberOfDocuments).iterator.flatMap { id =>
      Iterator(createDocEntryFor(index, id), s"""{"payload":"$payload"}""")
    }
  }

  private def createDocEntryFor(index: String, docId: Int) = {
    if (Version.greaterOrEqualThan(esVersionUsed, 7, 0, 0)) {
      s"""{"create":{"_index":"$index","_id":"$docId"}}"""
    } else {
      s"""{"create":{"_index":"$index","_type":"doc","_id":"$docId"}}"""
    }
  }

  private final class ThrottledEntity(content: Array[Byte]) extends AbstractHttpEntity {

    override def isRepeatable: Boolean = true

    override def isStreaming: Boolean = false

    override def getContentLength: Long = content.length.toLong

    override def getContent: InputStream = new ByteArrayInputStream(content)

    override def writeTo(outStream: OutputStream): Unit = {
      content.grouped(uploadChunkSize).foreach { chunk =>
        outStream.write(chunk)
        outStream.flush()
        Thread.sleep(pauseBetweenChunks.toMillis)
      }
    }

  }

}
