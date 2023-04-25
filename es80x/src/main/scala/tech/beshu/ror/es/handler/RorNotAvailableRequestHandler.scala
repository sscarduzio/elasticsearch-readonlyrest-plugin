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
package tech.beshu.ror.es.handler

import cats.Show
import cats.implicits.toShow
import org.elasticsearch.ElasticsearchException
import org.elasticsearch.rest.RestStatus
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.configuration.RorBootConfiguration
import tech.beshu.ror.configuration.RorBootConfiguration.{RorFailedToStartResponse, RorNotStartedResponse}
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.RorNotAvailableRequestHandler.RorNotAvailableResponse
import tech.beshu.ror.es.handler.response.ForbiddenResponse

import scala.jdk.CollectionConverters._

final class RorNotAvailableRequestHandler(config: RorBootConfiguration) {

  def handleRorNotReadyYet(esContext: EsContext): Unit = {
    val response = prepareNotReadyYetResponse()
    esContext.listener.onFailure(response)
  }

  def handleRorFailedToStart(esContext: EsContext): Unit = {
    val response = prepareFailedToStartResponse()
    esContext.listener.onFailure(response)
  }

  private def prepareNotReadyYetResponse() = {
    config.rorNotStartedResponse.httpCode match {
      case RorNotStartedResponse.HttpCode.`403` =>
        ForbiddenResponse.createRorNotReadyYetResponse()
      case RorNotStartedResponse.HttpCode.`503` =>
        RorNotAvailableResponse.createRorNotReadyYetResponse()
    }
  }

  private def prepareFailedToStartResponse() = {
    config.rorFailedToStartResponse.httpCode match {
      case RorFailedToStartResponse.HttpCode.`403` =>
        ForbiddenResponse.createRorStartingFailureResponse()
      case RorFailedToStartResponse.HttpCode.`503` =>
        RorNotAvailableResponse.createRorStartingFailureResponse()
    }
  }
}

private object RorNotAvailableRequestHandler {

  class RorNotAvailableResponse private(cause: RorNotAvailableResponse.Cause)
    extends ElasticsearchException(GlobalSettings.defaultForbiddenRequestMessage) {

    addMetadata("es.due_to", List(cause).map(_.show).asJava)

    override def status(): RestStatus = RestStatus.SERVICE_UNAVAILABLE
  }

  object RorNotAvailableResponse {

    sealed trait Cause
    object Cause {
      case object RorNotReadyYet extends Cause
      case object RorFailedToStart extends Cause
    }

    private implicit val causeShow: Show[Cause] = Show.show {
      case Cause.RorNotReadyYet => "READONLYREST_NOT_READY_YET"
      case Cause.RorFailedToStart => "READONLYREST_FAILED_TO_START"
    }

    def createRorStartingFailureResponse(): RorNotAvailableResponse =
      new RorNotAvailableResponse(Cause.RorFailedToStart)

    def createRorNotReadyYetResponse(): RorNotAvailableResponse =
      new RorNotAvailableResponse(Cause.RorNotReadyYet)
  }
}
