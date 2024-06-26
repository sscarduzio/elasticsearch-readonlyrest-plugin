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
package tech.beshu.ror.unit.acl.domain

import eu.timepit.refined.auto._
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.TestsUtils.unsafeNes

class IndexNameTests extends AnyWordSpec {

  "indexName.match" should {
    "follow given rules" in {
      (clusterIndexName("test*") matches clusterIndexName("test*")) should be(true)
      (clusterIndexName("test*") matches clusterIndexName("te*")) should be(false)
      (clusterIndexName("test*") matches clusterIndexName("tests*")) should be(true)
      (clusterIndexName("test*") matches clusterIndexName("test")) should be(true)
      (clusterIndexName("test*") matches clusterIndexName("te")) should be(false)
      (clusterIndexName("test*") matches clusterIndexName("tests")) should be(true)

      (clusterIndexName("test") matches clusterIndexName("test*")) should be(false)
      (clusterIndexName("test") matches clusterIndexName("te*")) should be(false)
      (clusterIndexName("test") matches clusterIndexName("tests*")) should be(false)
      (clusterIndexName("test") matches clusterIndexName("test")) should be(true)
      (clusterIndexName("test") matches clusterIndexName("te")) should be(false)
      (clusterIndexName("test") matches clusterIndexName("tests")) should be(false)
    }
  }
}