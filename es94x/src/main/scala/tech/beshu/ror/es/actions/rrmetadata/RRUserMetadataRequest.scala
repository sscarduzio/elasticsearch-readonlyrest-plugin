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
package tech.beshu.ror.es.actions.rrmetadata

import cats.implicits.*
import org.elasticsearch.action.{ActionRequest, ActionRequestValidationException}
import tech.beshu.ror.accesscontrol.domain.{Header, UriPath}
import tech.beshu.ror.accesscontrol.request.UserMetadataRequestContext.{UserMetadataApiVersion, UserMetadataApiVersionCreationError}
import tech.beshu.ror.es.actions.RorActionRequest
import tech.beshu.ror.implicits.*

class RRUserMetadataRequest(apiPath: UriPath,
                            licenseTypeHeader: Option[Header])
  extends ActionRequest with RorActionRequest {

  lazy val apiVersion: UserMetadataApiVersion = {
    UserMetadataApiVersion
      .from(apiPath, licenseTypeHeader)
      .getOrElse(throw ShouldAlreadyBeValidatedIllegalState)
  }

  override def validate(): ActionRequestValidationException = {
    UserMetadataApiVersion.from(apiPath, licenseTypeHeader) match {
      case Left(UserMetadataApiVersionCreationError.NoRequestedHeaderValue) =>
        wrongRorLicenseHeaderValidationException(cause = "missing")
      case Left(UserMetadataApiVersionCreationError.RorKbnLicenseTypeInvalidValue) =>
        wrongRorLicenseHeaderValidationException(cause = "invalid")
      case Right(_) =>
        null
    }
  }

  private def wrongRorLicenseHeaderValidationException(cause: String) = {
    val e = new ActionRequestValidationException()
    e.addValidationError(s"${Header.Name.rorKbnLicenseType.show} header is $cause")
    e
  }

  private object ShouldAlreadyBeValidatedIllegalState extends IllegalStateException(
    "Cannot prepare Api Version object. It's invalid state. Should be already validated in the RRUserMetadataRequest#validate() method!"
  )
}
