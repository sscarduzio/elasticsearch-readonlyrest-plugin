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
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.utils.{MatcherWithWildcards, ZeroKnowledgeIndexFilter}
import tech.beshu.ror.accesscontrol.blocks.rules.utils.{MatcherWithWildcardsScalaAdapter, ZeroKnowledgeIndexFilterScalaAdapter, ZeroKnowledgeRepositoryFilterScalaAdapter}
import tech.beshu.ror.accesscontrol.blocks.rules.utils.ZeroKnowledgeIndexFilterScalaAdapter.CheckResult
import tech.beshu.ror.utils.TestsUtils.StringOps

class ZeroKnowledgeIndexFilterTests extends WordSpec {

  "ZeroKnowledgeIndexFilter check" when {
    "one element is passed" in {
      val matcher = new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(Sets.newHashSet("a*")))

      val res1 = new ZeroKnowledgeIndexFilterScalaAdapter(new ZeroKnowledgeIndexFilter(true))
        .check(Set(IndexName("*".nonempty)), matcher)
      res1 should be(CheckResult.Ok(Set(IndexName("a*".nonempty))))

      val res2 = new ZeroKnowledgeIndexFilterScalaAdapter(new ZeroKnowledgeIndexFilter(true))
        .check(Set(IndexName("b".nonempty)), matcher)
      res2 should be(CheckResult.Failed)
    }
    "two elements are passed" in {
      val matcher = new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(Sets.newHashSet("a1*")))

      val res1 = new ZeroKnowledgeIndexFilterScalaAdapter(new ZeroKnowledgeIndexFilter(true))
        .check(Set(IndexName("a*".nonempty)), matcher)
      res1 should be(CheckResult.Ok(Set(IndexName("a1*".nonempty))))
    }
    "two patterns in matcher" in {
      val matcher = new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(Sets.newHashSet("b:*", "a*")))

      val res1 = new ZeroKnowledgeIndexFilterScalaAdapter(new ZeroKnowledgeIndexFilter(true))
        .check(Set(IndexName("*".nonempty)), matcher)
      res1 should be(CheckResult.Ok(Set(IndexName("a*".nonempty))))
    }
  }
}
