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
package tech.beshu.ror.accesscontrol.request

import tech.beshu.ror.accesscontrol.blocks.BlockContext.UserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.{GroupIdLike, Header, RorKbnLicenseType, UriPath}
import tech.beshu.ror.accesscontrol.request.UserMetadataRequestContext.UserMetadataApiVersion
import tech.beshu.ror.accesscontrol.request.UserMetadataRequestContext.UserMetadataApiVersionCreationError.{NoRequestedHeaderValue, RorKbnLicenseTypeInvalidValue}

import scala.language.implicitConversions

trait UserMetadataRequestContext extends RequestContext {

  override type BLOCK_CONTEXT <: UserMetadataRequestBlockContext

  def apiVersion: UserMetadataApiVersion

  override def currentGroupId: Option[GroupIdLike.GroupId] = {
    apiVersion match {
      case UserMetadataApiVersion.V1 => super.currentGroupId
      case UserMetadataApiVersion.V2(_) => None
    }
  }

}
object UserMetadataRequestContext {

  type Aux[B <: UserMetadataRequestBlockContext] = UserMetadataRequestContext {type BLOCK_CONTEXT = B}

  sealed trait UserMetadataApiVersion
  object UserMetadataApiVersion {
    case object V1 extends UserMetadataApiVersion // Old format (current_user)
    final case class V2(licenseType: RorKbnLicenseType) extends UserMetadataApiVersion // New format (user)

    def from(requestPath: UriPath, licenseTypeHeader: Option[Header]): Either[UserMetadataApiVersionCreationError, UserMetadataApiVersion] = {
      if (requestPath.isUserMetadataPath) {
        for {
          header <- licenseTypeHeader.toRight(left = NoRequestedHeaderValue)
          licenseType <- RorKbnLicenseType.from(header.value.value).left.map { case () => RorKbnLicenseTypeInvalidValue }
        } yield UserMetadataApiVersion.V2(licenseType)
      } else {
        Right(UserMetadataApiVersion.V1)
      }
    }
  }

  sealed trait UserMetadataApiVersionCreationError
  object UserMetadataApiVersionCreationError {
    case object NoRequestedHeaderValue extends UserMetadataApiVersionCreationError
    case object RorKbnLicenseTypeInvalidValue extends UserMetadataApiVersionCreationError
  }
}