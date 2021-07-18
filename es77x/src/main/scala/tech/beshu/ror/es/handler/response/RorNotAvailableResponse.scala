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
package tech.beshu.ror.es.handler.response

import org.elasticsearch.ElasticsearchException
import org.elasticsearch.rest.RestStatus

class RorNotAvailableResponse private(reason: String)
  extends ElasticsearchException(reason) {

  override def status(): RestStatus = RestStatus.SERVICE_UNAVAILABLE
}

object RorNotAvailableResponse {

  def createRorStartingFailureResponse(): RorNotAvailableResponse =
    new RorNotAvailableResponse(s"ReadonlyREST failed to start")

  def createRorNotReadyYetResponse(): RorNotAvailableResponse =
    new RorNotAvailableResponse("Waiting for ReadonlyREST start")

  def createRorNotEnabledResponse(): RorNotAvailableResponse =
    new RorNotAvailableResponse("ReadonlyREST plugin was disabled in settings")
}
