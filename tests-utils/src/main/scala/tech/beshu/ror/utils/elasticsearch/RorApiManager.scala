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

import org.apache.commons.lang.StringEscapeUtils.escapeJava
import org.apache.http.HttpResponse
import org.apache.http.client.methods.{HttpDelete, HttpGet, HttpPost}
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JSON, JsonResponse}
import tech.beshu.ror.utils.httpclient.RestClient

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

class RorApiManager(client: RestClient,
                    esVersion: String,
                    override val additionalHeaders: Map[String, String] = Map.empty)
  extends BaseManager(client) {

  private lazy val documentManager = new DocumentManager(client, esVersion)

  def fetchMetadata(): JsonResponse = {
    call(createUserMetadataRequest(None), new JsonResponse(_))
  }

  def fetchMetadata(preferredGroup: String): JsonResponse = {
    call(createUserMetadataRequest(Some(preferredGroup)), new JsonResponse(_))
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

  def updateRorTestConfig(config: String, ttl: Option[FiniteDuration] = None): JsonResponse = {
    call(createUpdateRorTestConfigRequest(config, ttl), new JsonResponse(_))
  }

  def invalidateRorTestConfig(): JsonResponse = {
    call(createInvalidateRorTestConfigRequest(), new JsonResponse(_))
  }

  def reloadRorConfig(): JsonResponse = {
    call(createReloadRorConfigRequest(), new JsonResponse(_))
  }

  def configureImpersonationMocks(payload: JSON): RorApiResponse = {
    call(createConfigureImpersonationMocksRequest(payload), new RorApiResponse(_))
  }

  def invalidateImpersonationMocks(): RorApiResponse = {
    call(createInvalidateImpersonationMocksRequest(), new RorApiResponse(_))
  }

  def insertInIndexConfigDirectlyToRorIndex(rorConfigIndex: String,
                                            config: String): JsonResponse = {
    documentManager.createFirstDoc(
      index = rorConfigIndex,
      content = ujson.read(rorConfigIndexDocumentContentFrom(config))
    )
  }

  private def createUserMetadataRequest(preferredGroup: Option[String]) = {
    val request = new HttpGet(client.from("/_readonlyrest/metadata/current_user"))
    preferredGroup.foreach(request.addHeader("x-ror-current-group", _))
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

  private def createUpdateRorTestConfigRequest(config: String,
                                               ttl: Option[FiniteDuration] = None) = {
    val request = new HttpPost(client.from("/_readonlyrest/admin/config/test"))
    request.addHeader("Content-Type", "application/json")
    ttl.foreach(t => request.addHeader("x-ror-test-settings-ttl", t.toString()))
    request.setEntity(new StringEntity(rorConfigIndexDocumentContentFrom(config)))
    request
  }

  private def rorConfigIndexDocumentContentFrom(config: String) = {
    s"""{"settings": "${escapeJava(config)}"}"""
  }

  private def createInvalidateRorTestConfigRequest() = {
    new HttpDelete(client.from("/_readonlyrest/admin/config/test"))
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
    val request = new HttpPost(client.from("/_readonlyrest/admin/authmock"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(payload)))
    request
  }

  private def createInvalidateImpersonationMocksRequest() = {
    new HttpDelete(client.from("/_readonlyrest/admin/authmock"))
  }

  private def createLoadRorCurrentConfigRequest(additionalParams: Map[String, String]) = {
    new HttpGet(client.from("/_readonlyrest/admin/config/load", additionalParams.asJava))
  }

  final class RorApiResponse(override val response: HttpResponse) extends JsonResponse(response) {

    def forceOk(): this.type = {
      force()
      val status = responseJson("status").str
      if (status != "ok") throw new IllegalStateException(s"Expected business status 'ok' but got '$status'}; Message: '${responseJson.obj.get("message").map(_.str).getOrElse("[none]")}'")
      this
    }
  }
}
