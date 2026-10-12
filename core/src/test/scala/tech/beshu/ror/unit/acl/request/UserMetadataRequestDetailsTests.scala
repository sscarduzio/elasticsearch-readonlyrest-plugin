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
package tech.beshu.ror.unit.acl.request

import eu.timepit.refined.types.string.NonEmptyString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.{Header, RorKbnLicenseType}
import tech.beshu.ror.accesscontrol.request.UserMetadataRequestContext.Details
import tech.beshu.ror.accesscontrol.request.UserMetadataRequestContext.DetailsCreationError.{
  AmbiguousRequestedHeaderValue,
  NoRequestedHeaderValue,
  RorKbnLicenseTypeInvalidValue
}
import tech.beshu.ror.utils.uniquelist.UniqueList

class UserMetadataRequestDetailsTests extends AnyWordSpec with Matchers {

  "Details.from" should {
    "read the license type from the single license type header" in {
      Details.from(Header.findSingleHeader(Header.Name.rorKbnLicenseType, in = headers("pro"))) should be(
        Right(Details(RorKbnLicenseType.Pro))
      )
    }
    "report a missing header when the request has no license type header" in {
      Details.from(Header.findSingleHeader(Header.Name.rorKbnLicenseType, in = headers())) should be(
        Left(NoRequestedHeaderValue)
      )
    }
    "report an ambiguous header when the request has two license type values" in {
      Details.from(Header.findSingleHeader(Header.Name.rorKbnLicenseType, in = headers("pro", "free"))) should be(
        Left(AmbiguousRequestedHeaderValue)
      )
    }
    "report an invalid value when the license type does not parse" in {
      Details.from(Header.findSingleHeader(Header.Name.rorKbnLicenseType, in = headers("unknown"))) should be(
        Left(RorKbnLicenseTypeInvalidValue)
      )
    }
  }

  private def headers(licenseTypes: String*): UniqueList[Header] = UniqueList.from(
    licenseTypes.map(value => Header(Header.Name.rorKbnLicenseType, NonEmptyString.unsafeFrom(value)))
  )

}
