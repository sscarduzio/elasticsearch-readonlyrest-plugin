package tech.beshu.ror.unit.acl.blocks.rules.utils

import com.google.common.collect.Sets
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.aDomain.IndexName
import tech.beshu.ror.utils.MatcherWithWildcards
import tech.beshu.ror.ZeroKnowledgeIndexFilter
import tech.beshu.ror.acl.blocks.rules.utils.{MatcherWithWildcardsScalaAdapter, ZeroKnowledgeIndexFilterScalaAdapter}
import tech.beshu.ror.acl.blocks.rules.utils.ZeroKnowledgeIndexFilterScalaAdapter.CheckResult

class ZeroKnowledgeIndexFilterTests extends WordSpec {

  "ZeroKnowledgeIndexFilter check" when {
    "one element is passed" in {
      val matcher = new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(Sets.newHashSet("a*")))

      val res1 = new ZeroKnowledgeIndexFilterScalaAdapter(new ZeroKnowledgeIndexFilter(true))
        .check(Set(IndexName("*")), matcher)
      res1 should be(CheckResult.Ok(Set(IndexName("a*"))))

      val res2 = new ZeroKnowledgeIndexFilterScalaAdapter(new ZeroKnowledgeIndexFilter(true))
        .check(Set(IndexName("b")), matcher)
      res2 should be(CheckResult.Failed)
    }
    "two elements are passed" in {
      val matcher = new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(Sets.newHashSet("a1*")))

      val res1 = new ZeroKnowledgeIndexFilterScalaAdapter(new ZeroKnowledgeIndexFilter(true))
        .check(Set(IndexName("a*")), matcher)
      res1 should be(CheckResult.Ok(Set(IndexName("a1*"))))
    }
    "two patterns in matcher" in {
      val matcher = new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(Sets.newHashSet("b:*", "a*")))

      val res1 = new ZeroKnowledgeIndexFilterScalaAdapter(new ZeroKnowledgeIndexFilter(true))
        .check(Set(IndexName("*")), matcher)
      res1 should be(CheckResult.Ok(Set(IndexName("a*"))))
    }
  }
}
