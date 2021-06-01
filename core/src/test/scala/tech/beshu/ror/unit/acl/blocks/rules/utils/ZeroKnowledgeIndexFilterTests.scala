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
package tech.beshu.ror.unit.acl.blocks.rules.utils

import com.google.common.collect.Sets
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.matchers.ZeroKnowledgeIndexFilterScalaAdapter.CheckResult
import tech.beshu.ror.accesscontrol.matchers.{MatcherWithWildcardsScalaAdapter, ZeroKnowledgeIndexFilterScalaAdapter}
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.utils.ZeroKnowledgeIndexFilter
import eu.timepit.refined.auto._
import tech.beshu.ror.utils.TestsUtils._

class ZeroKnowledgeIndexFilterTests extends AnyWordSpec {

  "ZeroKnowledgeIndexFilter check" when {
    "one element is passed" in {
      val matcher = MatcherWithWildcardsScalaAdapter.fromJavaSetString[IndexName](Sets.newHashSet("a*"))

      val res1 = new ZeroKnowledgeIndexFilterScalaAdapter(new ZeroKnowledgeIndexFilter(true))
        .check(Set(indexName("*")), matcher)
      res1 should be(CheckResult.Ok(Set(indexName("a*"))))

      val res2 = new ZeroKnowledgeIndexFilterScalaAdapter(new ZeroKnowledgeIndexFilter(true))
        .check(Set(indexName("b")), matcher)
      res2 should be(CheckResult.Failed)
    }
    "two elements are passed" in {
      val matcher = MatcherWithWildcardsScalaAdapter.fromJavaSetString[IndexName](Sets.newHashSet("a1*"))

      val res1 = new ZeroKnowledgeIndexFilterScalaAdapter(new ZeroKnowledgeIndexFilter(true))
        .check(Set(indexName("a*")), matcher)
      res1 should be(CheckResult.Ok(Set(indexName("a1*"))))
    }
    "two patterns in matcher" in {
      val matcher = MatcherWithWildcardsScalaAdapter.fromJavaSetString[IndexName](Sets.newHashSet("b:*", "a*"))

      val res1 = new ZeroKnowledgeIndexFilterScalaAdapter(new ZeroKnowledgeIndexFilter(true))
        .check(Set(indexName("*")), matcher)
      res1 should be(CheckResult.Ok(Set(indexName("a*"))))
    }
  }
}
