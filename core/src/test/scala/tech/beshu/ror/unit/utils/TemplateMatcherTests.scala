package tech.beshu.ror.unit.utils

import org.scalatest.prop.TableDrivenPropertyChecks._
import org.scalatest.WordSpec
import org.scalatest.Matchers._
import tech.beshu.ror.accesscontrol.blocks.rules.utils.TemplateMatcher
import tech.beshu.ror.accesscontrol.domain.IndexName

class TemplateMatcherTests extends WordSpec {

  "A TemplateMatcher" should {
    "be able to filter only allowed template indices patterns" in {
      val templatePatterns =
        Table(
          ("template patterns", "allowed indices", "filtered patterns"),
          (Set("dev*"), Set("dev*"), Set("dev*")),
          (Set("dev*"), Set("dev123*"), Set("dev*")),
          (Set("dev*"), Set("dev1234"), Set("dev*")),
          (Set("dev*"), Set("de*"), Set("dev*")),
          (Set("dev1234"), Set("dev123*"), Set("dev1234")),
          (Set("dev1*", "dev2*"), Set("dev*"), Set("dev1*","dev2*")),
          (Set("dev*"), Set("dev1*", "dev2*"), Set("dev*")),
          (Set("dev3*"), Set("dev1*", "dev2*"), Set.empty),
        )

      forAll(templatePatterns) { (templatePatterns, allowedIndices, filteredPatterns) =>
        TemplateMatcher.filterAllowedTemplateIndexPatterns(
          templatePatterns.map(IndexName.fromUnsafeString),
          allowedIndices.map(IndexName.fromUnsafeString)
        ) should be (filteredPatterns.map(IndexName.fromUnsafeString))
      }
    }
    "be able to narrow allowed template indices patterns" in {
      val templatePatterns =
        Table(
          ("template patterns", "allowed indices", "origin patterns and narrowed patterns"),
          (Set("*"), Set("custom_*"), Set(("*", "custom_*"))),
          (Set("custom_index_*"), Set("custom_*"), Set(("custom_index_*", "custom_index_*"))),
          (Set("custom_*"), Set("custom_*"), Set(("custom_*", "custom_*"))),
          (Set("dev*"), Set("dev1*", "dev2*"), Set(("dev*", "dev1*"), ("dev*", "dev2*"))),
          (Set("dev3*"), Set("dev1*", "dev2*"), Set.empty),
          (Set("dev*"), Set("dev1234"), Set(("dev*", "dev1234"))),
        )

      forAll(templatePatterns) { (templatePatterns, allowedIndices, filteredPatterns) =>
        TemplateMatcher.narrowAllowedTemplateIndexPatterns(
          templatePatterns.map(IndexName.fromUnsafeString),
          allowedIndices.map(IndexName.fromUnsafeString)
        ) should be (filteredPatterns.map { case (a, b) => (IndexName.fromUnsafeString(a), IndexName.fromUnsafeString(b)) })
      }
    }
  }
}
