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

import cats.Show
import cats.implicits.toShow
import org.elasticsearch.ElasticsearchException
import org.elasticsearch.rest.RestStatus
import tech.beshu.ror.accesscontrol.factory.GlobalSettings

import scala.jdk.CollectionConverters._

class ServiceNotAvailableResponse private(cause: ServiceNotAvailableResponse.Cause)
  extends ElasticsearchException(GlobalSettings.defaultForbiddenRequestMessage) {

  addMetadata("es.due_to", List(cause).map(_.show).asJava)

  override def status(): RestStatus = RestStatus.SERVICE_UNAVAILABLE
}

object ServiceNotAvailableResponse {

  sealed trait Cause
  object Cause {
    case object RorNotReadyYet extends Cause
    case object RorFailedToStart extends Cause
  }

  private implicit val causeShow: Show[Cause] = Show.show {
    case Cause.RorNotReadyYet => "READONLYREST_NOT_READY_YET"
    case Cause.RorFailedToStart => "READONLYREST_FAILED_TO_START"
  }

  def createRorStartingFailureResponse(): ServiceNotAvailableResponse =
    new ServiceNotAvailableResponse(Cause.RorFailedToStart)

  def createRorNotReadyYetResponse(): ServiceNotAvailableResponse =
    new ServiceNotAvailableResponse(Cause.RorNotReadyYet)
}
