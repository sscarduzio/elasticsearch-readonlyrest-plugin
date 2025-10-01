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

import tech.beshu.ror.settings.es.RorBootConfiguration
import tech.beshu.ror.settings.es.RorBootConfiguration.{RorFailedToStartResponse, RorNotStartedResponse}
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.response.{ForbiddenResponse, ServiceNotAvailableResponse}

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
        ServiceNotAvailableResponse.createRorNotReadyYetResponse()
    }
  }

  private def prepareFailedToStartResponse() = {
    config.rorFailedToStartResponse.httpCode match {
      case RorFailedToStartResponse.HttpCode.`403` =>
        ForbiddenResponse.createRorStartingFailureResponse()
      case RorFailedToStartResponse.HttpCode.`503` =>
        ServiceNotAvailableResponse.createRorStartingFailureResponse()
    }
  }
}
