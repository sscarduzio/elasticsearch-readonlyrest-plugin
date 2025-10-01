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

import cats.implicits.*
import org.apache.commons.text.StringEscapeUtils.escapeJava
import org.apache.http.HttpResponse
import org.apache.http.client.methods.{HttpDelete, HttpGet, HttpPost}
import org.apache.http.entity.StringEntity
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import tech.beshu.ror.utils.TestUjson.ujson
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.httpclient.RestClient

import scala.concurrent.duration.FiniteDuration

class RorApiManager(client: RestClient,
                    esVersion: String,
                    override val additionalHeaders: Map[String, String] = Map.empty)
  extends BaseManager(client, esVersion, esNativeApi = false) {

  final lazy val documentManager = new DocumentManager(client, esVersion)

  def fetchMetadata(preferredGroupId: Option[String] = None,
                    correlationId: Option[String] = None): RorApiJsonResponse = {
    call(createUserMetadataRequest(preferredGroupId, correlationId), new RorApiJsonResponse(_))
  }

  def sendAuditEvent(payload: JSON): RorApiJsonResponse = {
    call(createSendAuditEventRequest(payload), new RorApiJsonResponse(_))
  }

  def getRorFileConfig: RorApiJsonResponse = {
    call(createGetRorFileConfigRequest(), new RorApiJsonResponse(_))
  }

  def getRorInIndexConfig: RorApiJsonResponse = {
    call(createGetRorInIndexConfigRequest(), new RorApiJsonResponse(_))
  }

  def updateRorInIndexConfig(config: String): RorApiResponseWithBusinessStatus = {
    call(createUpdateRorInIndexConfigRequest(config), new RorApiResponseWithBusinessStatus(_))
  }

  def updateRorInIndexConfigRaw(rawRequestBody: String): RorApiResponseWithBusinessStatus = {
    call(createUpdateRorInIndexConfigRequestFromRaw(rawRequestBody), new RorApiResponseWithBusinessStatus(_))
  }

  def currentRorTestConfig: RorApiJsonResponse = {
    call(createGetTestConfigRequest, new RorApiJsonResponse(_))
  }

  def updateRorTestConfig(config: String, ttl: FiniteDuration = 30.minutes): RorApiResponseWithBusinessStatus = {
    call(createUpdateRorTestConfigRequest(config, ttl), new RorApiResponseWithBusinessStatus(_))
  }

  def updateRorTestConfigRaw(rawRequestBody: String): RorApiResponseWithBusinessStatus = {
    call(createUpdateRorTestConfigRequest(rawRequestBody), new RorApiResponseWithBusinessStatus(_))
  }

  def invalidateRorTestConfig(): RorApiResponseWithBusinessStatus = {
    call(createInvalidateRorTestConfigRequest(), new RorApiResponseWithBusinessStatus(_))
  }

  def currentRorLocalUsers: RorApiJsonResponse = {
    call(createProvideLocalUsersRequest(), new RorApiJsonResponse(_))
  }

  def reloadRorConfig(): RorApiJsonResponse = {
    call(createReloadRorConfigRequest(), new RorApiJsonResponse(_))
  }

  def configureImpersonationMocks(payload: JSON): RorApiResponseWithBusinessStatus = {
    call(createConfigureImpersonationMocksRequest(payload), new RorApiResponseWithBusinessStatus(_))
  }

  def currentMockedServices(): RorApiResponseWithBusinessStatus = {
    call(provideAuthMocksRequest(), new RorApiResponseWithBusinessStatus(_))
  }

  def invalidateImpersonationMocks(): RorApiResponseWithBusinessStatus = {
    val payload = ujson.read(
      s"""
         | {
         |   "services": []
         | }
         |""".stripMargin
    )
    call(createConfigureImpersonationMocksRequest(payload), new RorApiResponseWithBusinessStatus(_))
  }

  def insertInIndexConfigDirectlyToRorIndex(rorConfigIndex: String, config: String): documentManager.JsonResponse = {
    documentManager.createFirstDoc(
      index = rorConfigIndex,
      content = ujson.read(rorConfigIndexDocumentContentFrom(config))
    )
  }

  private def createUserMetadataRequest(preferredGroupId: Option[String],
                                        correlationId: Option[String]) = {
    val request = new HttpGet(client.from("/_readonlyrest/metadata/current_user"))
    preferredGroupId.foreach(request.addHeader("x-ror-current-group", _))
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

  final class RorApiJsonResponse(override val response: HttpResponse)
    extends JsonResponse(response)

  final class RorApiResponseWithBusinessStatus(override val response: HttpResponse)
    extends JsonResponse(response) {

    def forceOkStatus(): this.type = {
      force()
      if (businessStatus =!= "OK") {
        throw new IllegalStateException(
          s"""
             |Expected business status 'OK' but got:
             |
             |HTTP $responseCode
             |${responseJson.toString()}
             |""".stripMargin
        )
      }
      this
    }

    def forceOKStatusOrConfigAlreadyLoaded(): this.type = {
      force()
      if (businessStatus === "OK" || isConfigAlreadyLoaded) {
        this
      } else {
        throw new IllegalStateException(
          s"""
             |Expected business status 'OK' or info about already loaded config, but got:"
             |
             |HTTP $responseCode
             |${responseJson.toString()}
             |""".stripMargin
        )
      }
    }

    private def isConfigAlreadyLoaded = {
      businessStatus == "KO" && message.contains("already loaded")
    }

    private def businessStatus = responseJson("status").str.toUpperCase()

    private def message = responseJson.obj.get("message").map(_.str).getOrElse("[none]")
  }
}
