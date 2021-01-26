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
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.domain.IndexName

class IndexNameTests extends WordSpec {

  "IndexName.match" should {
    "follow given rules" in {
      (IndexName("test*") matches IndexName("test*")) should be(true)
      (IndexName("test*") matches IndexName("te*")) should be(false)
      (IndexName("test*") matches IndexName("tests*")) should be(true)
      (IndexName("test*") matches IndexName("test")) should be(true)
      (IndexName("test*") matches IndexName("te")) should be(false)
      (IndexName("test*") matches IndexName("tests")) should be(true)

      (IndexName("test") matches IndexName("test*")) should be(false)
      (IndexName("test") matches IndexName("te*")) should be(false)
      (IndexName("test") matches IndexName("tests*")) should be(false)
      (IndexName("test") matches IndexName("test")) should be(true)
      (IndexName("test") matches IndexName("te")) should be(false)
      (IndexName("test") matches IndexName("tests")) should be(false)
    }
  }
}