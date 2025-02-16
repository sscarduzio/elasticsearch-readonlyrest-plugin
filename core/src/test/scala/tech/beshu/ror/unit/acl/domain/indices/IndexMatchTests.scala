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

import eu.timepit.refined.auto.*
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.utils.TestsUtils.*

class IndexMatchTests extends AnyWordSpec {

  "IndexMatch#match" should {
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