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
import tech.beshu.ror.accesscontrol.domain.{Header, RorKbnLicenseType}
import tech.beshu.ror.accesscontrol.request.UserMetadataRequestContext.UserMetadataApiVersion
import tech.beshu.ror.es.actions.RorActionRequest
import tech.beshu.ror.implicits.*

class RRUserMetadataRequest(isNewApiPath: Boolean,
                            licenseTypeHeaderValue: Option[String])
  extends ActionRequest with RorActionRequest {

  lazy val apiVersion: UserMetadataApiVersion =
    if (isNewApiPath) {
      val apiVersion = for {
        value <- licenseTypeHeaderValue
        licenseType <- RorKbnLicenseType.from(value)
      } yield UserMetadataApiVersion.V2(licenseType)
      apiVersion.getOrElse(throw new IllegalStateException("Cannot prepare Api Version object. Should be already validated!"))
    } else {
      UserMetadataApiVersion.V1
    }

  override def validate(): ActionRequestValidationException = {
    if (isNewApiPath) {
      licenseTypeHeaderValue match {
        case None => wrongRorLicenseHeaderValidationException(cause = "missing")
        case Some(value) =>
          RorKbnLicenseType.from(value) match {
            case None => wrongRorLicenseHeaderValidationException(cause = "invalid")
            case Some(_) => null
          }
      }
    } else {
      null
    }
  }

  private def wrongRorLicenseHeaderValidationException(cause: String) = {
    val e = new ActionRequestValidationException()
    e.addValidationError(s"${Header.Name.rorKbnLicenseType.show} header is $cause")
    e
  }

}
