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
package tech.beshu.ror.utils.elasticsearch

import cats.implicits._
import org.apache.commons.lang.StringEscapeUtils.escapeJava
import org.apache.http.HttpResponse
import org.apache.http.client.methods.{HttpDelete, HttpGet, HttpPost}
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JSON, JsonResponse}
import tech.beshu.ror.utils.httpclient.RestClient

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

class RorApiManager(client: RestClient,
                    esVersion: String,
                    override val additionalHeaders: Map[String, String] = Map.empty)
  extends BaseManager(client) {

  private lazy val documentManager = new DocumentManager(client, esVersion)

  def fetchMetadata(preferredGroup: Option[String] = None,
                    correlationId: Option[String] = None): JsonResponse = {
    call(createUserMetadataRequest(preferredGroup, correlationId), new JsonResponse(_))
  }

  def sendAuditEvent(payload: JSON): JsonResponse = {
    call(createSendAuditEventRequest(payload), new JsonResponse(_))
  }

  def getRorFileConfig: JsonResponse = {
    call(createGetRorFileConfigRequest(), new JsonResponse(_))
  }

  def getRorInIndexConfig: JsonResponse = {
    call(createGetRorInIndexConfigRequest(), new JsonResponse(_))
  }

  def loadRorCurrentConfig(additionalParams: Map[String, String] = Map.empty): JsonResponse = {
    call(createLoadRorCurrentConfigRequest(additionalParams), new JsonResponse(_))
  }

  def updateRorInIndexConfig(config: String): JsonResponse = {
    call(createUpdateRorInIndexConfigRequest(config), new JsonResponse(_))
  }

  def updateRorInIndexConfigRaw(rawRequestBody: String): JsonResponse = {
    call(createUpdateRorInIndexConfigRequestFromRaw(rawRequestBody), new JsonResponse(_))
  }

  def currentRorTestConfig: JsonResponse = {
    call(createGetTestConfigRequest, new JsonResponse(_))
  }

  def updateRorTestConfig(config: String, ttl: FiniteDuration = FiniteDuration(30, TimeUnit.MINUTES)): RorApiResponse = {
    call(createUpdateRorTestConfigRequest(config, ttl), new RorApiResponse(_))
  }

  def updateRorTestConfigRaw(rawRequestBody: String): JsonResponse = {
    call(createUpdateRorTestConfigRequest(rawRequestBody), new JsonResponse(_))
  }

  def invalidateRorTestConfig(): RorApiResponse = {
    call(createInvalidateRorTestConfigRequest(), new RorApiResponse(_))
  }

  def currentRorLocalUsers: JsonResponse = {
    call(createProvideLocalUsersRequest(), new JsonResponse(_))
  }

  def reloadRorConfig(): JsonResponse = {
    call(createReloadRorConfigRequest(), new JsonResponse(_))
  }

  def configureImpersonationMocks(payload: JSON): RorApiResponse = {
    call(createConfigureImpersonationMocksRequest(payload), new RorApiResponse(_))
  }

  def currentMockedServices(): RorApiResponse = {
    call(provideAuthMocksRequest(), new RorApiResponse(_))
  }

  def invalidateImpersonationMocks(): RorApiResponse = {
    val payload = ujson.read(
      s"""
         | {
         |   "services": []
         | }
         |""".stripMargin
    )
    call(createConfigureImpersonationMocksRequest(payload), new RorApiResponse(_))
  }

  def insertInIndexConfigDirectlyToRorIndex(rorConfigIndex: String,
                                            config: String): JsonResponse = {
    documentManager.createFirstDoc(
      index = rorConfigIndex,
      content = ujson.read(rorConfigIndexDocumentContentFrom(config))
    )
  }

  private def createUserMetadataRequest(preferredGroup: Option[String],
                                        correlationId: Option[String]) = {
    val request = new HttpGet(client.from("/_readonlyrest/metadata/current_user"))
    preferredGroup.foreach(request.addHeader("x-ror-current-group", _))
    correlationId.foreach(request.addHeader("x-ror-correlation-id", _))
    request
  }

  private def createSendAuditEventRequest(payload: JSON) = {
    val request = new HttpPost(client.from("/_readonlyrest/admin/audit/event"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(
      payload.toString()
    ))
    request
  }

  private def createUpdateRorInIndexConfigRequest(config: String) = {
    val request = new HttpPost(client.from("/_readonlyrest/admin/config"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(rorConfigIndexDocumentContentFrom(config)))
    request
  }

  private def createUpdateRorInIndexConfigRequestFromRaw(rawRequestJson: String) = {
    val request = new HttpPost(client.from("/_readonlyrest/admin/config"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(rawRequestJson))
    request
  }

  private def createGetTestConfigRequest = {
    new HttpGet(client.from("/_readonlyrest/admin/config/test"))
  }

  private def createUpdateRorTestConfigRequest(config: String,
                                               ttl: FiniteDuration) = {
    val request = new HttpPost(client.from("/_readonlyrest/admin/config/test"))

    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(rorTestConfig(config, ttl)))
    request
  }

  private def createUpdateRorTestConfigRequest(rawRequestJson: String) = {
    val request = new HttpPost(client.from("/_readonlyrest/admin/config/test"))

    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(rawRequestJson))
    request
  }

  private def rorConfigIndexDocumentContentFrom(config: String) = {
    s"""{"settings": "${escapeJava(config)}"}"""
  }

  private def rorTestConfig(config: String, ttl: FiniteDuration) = {
    s"""{"settings": "${escapeJava(config)}", "ttl": "${ttl.toString()}"}"""
  }

  private def createInvalidateRorTestConfigRequest() = {
    new HttpDelete(client.from("/_readonlyrest/admin/config/test"))
  }

  private def createProvideLocalUsersRequest() = {
    new HttpGet(client.from("/_readonlyrest/admin/config/test/localusers"))
  }

  private def createGetRorFileConfigRequest() = {
    new HttpGet(client.from("/_readonlyrest/admin/config/file"))
  }

  private def createGetRorInIndexConfigRequest() = {
    new HttpGet(client.from("/_readonlyrest/admin/config"))
  }

  private def createReloadRorConfigRequest() = {
    val request = new HttpPost(client.from("/_readonlyrest/admin/refreshconfig"))
    request.addHeader("Content-Type", "application/json")
    request
  }

  private def createConfigureImpersonationMocksRequest(payload: JSON) = {
    val request = new HttpPost(client.from("/_readonlyrest/admin/config/test/authmock"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(payload)))
    request
  }

  private def provideAuthMocksRequest() = {
    new HttpGet(client.from("/_readonlyrest/admin/config/test/authmock"))
  }

  private def createLoadRorCurrentConfigRequest(additionalParams: Map[String, String]) = {
    new HttpGet(client.from("/_readonlyrest/admin/config/load", additionalParams))
  }

  final class RorApiResponse(override val response: HttpResponse) extends JsonResponse(response) {

    def forceOk(): this.type = {
      force()
      val status = responseJson("status").str
      if (status =!= "OK") throw new IllegalStateException(s"Expected business status 'OK' but got '$status'}; Message: '${responseJson.obj.get("message").map(_.str).getOrElse("[none]")}'")
      this
    }
  }
}
