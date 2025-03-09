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
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.RorAuditIndexTemplate
import tech.beshu.ror.accesscontrol.domain.RorAuditIndexTemplate.CreationError
import tech.beshu.ror.utils.TestsUtils.*

import java.time.Instant

class RorAuditIndexTemplateTests extends AnyWordSpec with Inside {

  private val template = RorAuditIndexTemplate.from("'.ror_'yyyy_MM").toOption.get

  "A RorAuditIndexTemplate" should {
    "provide a way to create an index from it" when {
      "template contains date pattern" in {
        val index = template.indexName(Instant.parse("2021-01-03T23:35:22.00Z"))
        index should be(indexName(".ror_2021_01"))
      }
      "template doesn't contain date pattern" in {
        inside(RorAuditIndexTemplate.from("'.ror'")) {
          case Right(templateWithoutDate) =>
            templateWithoutDate.indexName(Instant.now()) should be (indexName(".ror"))
        }
      }
    }
    "not be able to be created" when {
      "the string date pattern is malformed" in {
        RorAuditIndexTemplate.from("'.ror'_dddd_MMM") should be(Left(CreationError.ParsingError("Too many pattern letters: d")))
      }
    }
    "conform to index" which {
      "was created from the template" in {
        val index = template.indexName(Instant.now())
        template.conforms(index) should be(true)
      }
      "name has proper date format" in {
        template.conforms(indexName(".ror_2020_01")) should be(true)
      }
      "name started the same as template" in {
        template.conforms(indexName(".ror*")) should be(true)
      }
      "is wildcard" in {
        template.conforms(indexName("*")) should be(true)
      }
      "name is exactly the same as template (no date pattern used)" in {
        val noDatePatternTemplate = RorAuditIndexTemplate.from("'.ror'").toOption.get
        noDatePatternTemplate.conforms(indexName(".ror")) should be(true)
      }
    }
    "not conform to index" which {
      "name contains wildcard, but the pattern doesn't apply" in {
        template.conforms(indexName("ror*")) should be(false)
      }
      "name is the same as fixed part of template" in {
        template.conforms(indexName(".ror_")) should be(false)
      }
      "name totally differs" in {
        template.conforms(indexName("other")) should be(false)
      }
    }
  }
}
