package tech.beshu.ror.unit.utils

import org.scalatest.prop.TableDrivenPropertyChecks._
import org.scalatest.WordSpec
import org.scalatest.Matchers._
import tech.beshu.ror.accesscontrol.blocks.rules.utils.TemplateMatcher
import tech.beshu.ror.accesscontrol.domain.IndexName

class TemplateMatcherTests extends WordSpec {

  "A TemplateMatcher" should {
    "be able to narrow allowed template indices patterns" in {
      val templatePatterns =
        Table(
          ("template patterns", "allowed indices", "filtered patterns"),
          (Set("dev*"), Set("dev*"), Set("dev*")),
          (Set("dev*"), Set("dev123*"), Set("dev123*")),
          (Set("dev*"), Set("dev1234"), Set("dev1234")),
          (Set("dev*"), Set("de*"), Set("dev*")),
          (Set("dev1234"), Set("dev123*"), Set("dev1234")),
          (Set("dev1*", "dev2*"), Set("dev*"), Set("dev1*","dev2*")),
          (Set("dev*"), Set("dev1*", "dev2*"), Set("dev1*", "dev2*")),
          (Set("dev3*"), Set("dev1*", "dev2*"), Set.empty),
        )

      forAll(templatePatterns) { (templatePatterns, allowedIndices, filteredPatterns) =>
        TemplateMatcher.narrowAllowedTemplatesIndicesPatterns(
          templatePatterns.map(IndexName.fromUnsafeString),
          allowedIndices.map(IndexName.fromUnsafeString)
        ) should be (filteredPatterns.map(IndexName.fromUnsafeString))
      }
    }
    "be able to filter only allowed template indices patterns" in {
      val templatePatterns =
        Table(
          ("template patterns", "allowed indices", "filtered patterns"),
          (Set("dev*"), Set("dev*"), Set("dev*")),
          (Set("dev*"), Set("dev123*"), Set.empty),
          (Set("dev*"), Set("dev1234"), Set.empty),
          (Set("dev*"), Set("de*"), Set("dev*")),
          (Set("dev1234"), Set("dev123*"), Set("dev1234")),
          (Set("dev1*", "dev2*"), Set("dev*"), Set("dev1*","dev2*")),
          (Set("dev*"), Set("dev1*", "dev2*"), Set.empty),
          (Set("dev3*"), Set("dev1*", "dev2*"), Set.empty),
        )

      forAll(templatePatterns) { (templatePatterns, allowedIndices, filteredPatterns) =>
        TemplateMatcher.filterAllowedTemplatesIndicesPatterns(
          templatePatterns.map(IndexName.fromUnsafeString),
          allowedIndices.map(IndexName.fromUnsafeString)
        ) should be (filteredPatterns.map(IndexName.fromUnsafeString))
      }
    }
  }
}
