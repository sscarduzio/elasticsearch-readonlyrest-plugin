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

import java.time.Instant

import eu.timepit.refined.auto._
import org.scalatest.WordSpec
import org.scalatest.Matchers._
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorAuditIndexTemplate}

class RorAuditIndexTemplateTests extends WordSpec {

  // todo: do it better
  "A test" in {
    val template = RorAuditIndexTemplate.from("'.ror_'yyyy_MM").right.get
    val index = template.indexName(Instant.now())
    template.conforms(IndexName(".ror")) should be (false)
    template.conforms(IndexName("other")) should be (false)
    template.conforms(IndexName("ror*")) should be (false)
    template.conforms(IndexName(".ror_2020_01")) should be (true)
    template.conforms(IndexName("*")) should be (true)
    template.conforms(index)
    template.conforms(IndexName(".ror*")) should be (true)
  }
}
