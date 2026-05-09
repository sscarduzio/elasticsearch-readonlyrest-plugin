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
import tech.beshu.ror.accesscontrol.domain.{Header, RorKbnLicenseType}
import tech.beshu.ror.accesscontrol.request.UserMetadataRequestContext.DetailsCreationError.{NoRequestedHeaderValue, RorKbnLicenseTypeInvalidValue}

import scala.language.implicitConversions

trait UserMetadataRequestContext extends RequestContext {

  override type BLOCK_CONTEXT <: UserMetadataRequestBlockContext

  def details: UserMetadataRequestContext.Details

  override def currentGroupId = None

}

object UserMetadataRequestContext {

  type Aux[B <: UserMetadataRequestBlockContext] = UserMetadataRequestContext {type BLOCK_CONTEXT = B}

  final case class Details(licenseType: RorKbnLicenseType)

  object Details {
    def from(licenseTypeHeader: Option[Header]): Either[DetailsCreationError, Details] = {
      for {
        header <- licenseTypeHeader.toRight(left = NoRequestedHeaderValue)
        licenseType <- RorKbnLicenseType.from(header.value.value).left.map { case () => RorKbnLicenseTypeInvalidValue }
      } yield Details(licenseType)
    }
  }

  sealed trait DetailsCreationError
  object DetailsCreationError {
    case object NoRequestedHeaderValue extends DetailsCreationError
    case object RorKbnLicenseTypeInvalidValue extends DetailsCreationError
  }
}