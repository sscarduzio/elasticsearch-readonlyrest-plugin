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
package tech.beshu.ror.unit.acl.domain.indices

import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.IndexAttributeFilter

class IndexAttributeFilterTests extends AnyWordSpec {

  "IndexAttributeFilter.from" should {
    "expand to all attributes when both wildcards are expanded" in {
      IndexAttributeFilter.from(expandWildcardsOpen = true, expandWildcardsClosed = true) should be(
        IndexAttributeFilter.All
      )
    }
    "expand to all attributes when no wildcards are expanded (no attribute restriction)" in {
      IndexAttributeFilter.from(expandWildcardsOpen = false, expandWildcardsClosed = false) should be(
        IndexAttributeFilter.All
      )
    }
    "restrict to opened indices when only the open wildcard is expanded" in {
      IndexAttributeFilter.from(expandWildcardsOpen = true, expandWildcardsClosed = false) should be(
        IndexAttributeFilter.Opened
      )
    }
    "restrict to closed indices when only the closed wildcard is expanded" in {
      IndexAttributeFilter.from(expandWildcardsOpen = false, expandWildcardsClosed = true) should be(
        IndexAttributeFilter.Closed
      )
    }
  }

}
