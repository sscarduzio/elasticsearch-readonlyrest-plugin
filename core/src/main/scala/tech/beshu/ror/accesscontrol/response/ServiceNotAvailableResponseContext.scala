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
package tech.beshu.ror.accesscontrol.response

import cats.Show
import cats.implicits.*
import tech.beshu.ror.accesscontrol.factory.GlobalSettings

class ServiceNotAvailableResponseContext(cause: ServiceNotAvailableResponseContext.Cause) {
  val responseMessage: String = GlobalSettings.defaultForbiddenRequestMessage
  val causes: List[String] = List(cause.show)
}

object ServiceNotAvailableResponseContext {
  sealed trait Cause
  object Cause {
    case object RorNotReadyYet extends Cause
    case object RorFailedToStart extends Cause
  }

  private implicit val causeShow: Show[Cause] = Show.show {
    case Cause.RorNotReadyYet => "READONLYREST_NOT_READY_YET"
    case Cause.RorFailedToStart => "READONLYREST_FAILED_TO_START"
  }

  trait ResponseCreator[RESPONSE] {

    def create(context: ServiceNotAvailableResponseContext): RESPONSE

    final def createRorStartingFailureResponse(): RESPONSE =
      create(new ServiceNotAvailableResponseContext(Cause.RorFailedToStart))

    final def createRorNotReadyYetResponse(): RESPONSE =
      create(new ServiceNotAvailableResponseContext(Cause.RorNotReadyYet))

  }
}
