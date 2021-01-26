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
package tech.beshu.ror.unit.acl.blocks.rules

import eu.timepit.refined.auto._
import cats.data.{NonEmptyList, NonEmptySet}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Assertion, WordSpec}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{GeneralIndexRequestBlockContext, TemplateRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.IndicesRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.domain.Template.LegacyIndexTemplate
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.{LegacyTemplateAdding, LegacyTemplateDeleting, LegacyTemplateGetting}
import tech.beshu.ror.accesscontrol.domain.{Action, IndexName, IndexWithAliases, Template, TemplateName, TemplateNamePattern, TemplateOperation}
import tech.beshu.ror.accesscontrol.orders.indexOrder
import tech.beshu.ror.mocks.{MockGeneralIndexRequestContext, MockRequestContext, MockTemplateRequestContext}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class IndicesRuleTests extends WordSpec with MockFactory {

  "An IndicesRule" should {
    "match" when {
      "no index passed, one is configured, there is one real index" in {
        assertMatchRule(
          configured = NonEmptySet.of(index("test")),
          requestIndices = Set.empty,
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test"), Set.empty))
          ),
          found = Set(IndexName("test")),
        )
      }
      "'_all' passed, one is configured, there is one real index" in {
        assertMatchRule(
          configured = NonEmptySet.of(index("test")),
          requestIndices = Set(IndexName("_all")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test"), Set.empty))
          ),
          found = Set(IndexName("test"))
        )
      }
      "'*' passed, one is configured, there is one real index" in {
        assertMatchRule(
          configured = NonEmptySet.of(index("test")),
          requestIndices = Set(IndexName("*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test"), Set.empty))
          ),
          found = Set(IndexName("test"))
        )
      }
      "one full name index passed, one full name index configured, no real indices" in {
        assertMatchRule(
          configured = NonEmptySet.of(index("test")),
          requestIndices = Set(IndexName("test")),
          found = Set(IndexName("test"))
        )
      }
      "one wildcard index passed, one full name index configured, no real indices" in {
        assertMatchRule(
          configured = NonEmptySet.of(index("test")),
          requestIndices = Set(IndexName("te*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test"), Set.empty)),
          ),
          found = Set(IndexName("test"))
        )
      }
      "one full name index passed, one wildcard index configured, no real indices" in {
        assertMatchRule(
          configured = NonEmptySet.of(index("t*")),
          requestIndices = Set(IndexName("test")),
          found = Set(IndexName("test"))
        )
      }
      "two full name indexes passed, the same two full name indexes configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(index("test1"), index("test2")),
          requestIndices = Set(IndexName("test2"), IndexName("test1")),
          found = Set(IndexName("test2"), IndexName("test1"))
        )
      }
      "two full name indexes passed, one the same, one different index configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(index("test1"), index("test2")),
          requestIndices = Set(IndexName("test1"), IndexName("test3")),
          found = Set(IndexName("test1"))
        )
      }
      "two matching wildcard indexes passed, two full name indexes configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(index("test1"), index("test2")),
          requestIndices = Set(IndexName("*2"), IndexName("*1")),
          found = Set(IndexName("test1"), IndexName("test2"))
        )
      }
      "two full name indexes passed, two matching wildcard indexes configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(index("*1"), index("*2")),
          requestIndices = Set(IndexName("test2"), IndexName("test1")),
          found = Set(IndexName("test2"), IndexName("test1"))
        )
      }
      "two full name indexes passed, one matching full name and one non-matching wildcard index configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(index("test1"), index("*2")),
          requestIndices = Set(IndexName("test1"), IndexName("test3")),
          found = Set(IndexName("test1"))
        )
      }
      "one matching wildcard index passed and one non-matching full name index, two full name indexes configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(index("test1"), index("*2")),
          requestIndices = Set(IndexName("*1"), IndexName("test3")),
          found = Set(IndexName("test1"))
        )
      }
      "one full name alias passed, full name index related to that alias configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(index("test-index")),
          requestIndices = Set(IndexName("test-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test-index"), Set(IndexName("test-alias"))))
          ),
          found = Set(IndexName("test-index"))
        )
      }
      "wildcard alias passed, full name index related to alias matching passed one configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(index("test-index")),
          requestIndices = Set(IndexName("*-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test-index"), Set(IndexName("test-alias"))))
          ),
          found = Set(IndexName("test-index"))
        )
      }
      "one full name alias passed, wildcard index configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(index("*-index")),
          requestIndices = Set(IndexName("test-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test-index"), Set(IndexName("test-alias"))))
          ),
          found = Set(IndexName("test-index"))
        )
      }
      "one alias passed, only subset of alias indices configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(index("test-index1"), index("test-index2")),
          requestIndices = Set(IndexName("test-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(IndexName("test-index1"), Set(IndexName("test-alias"))),
              IndexWithAliases(IndexName("test-index2"), Set(IndexName("test-alias"))),
              IndexWithAliases(IndexName("test-index3"), Set(IndexName("test-alias"))),
              IndexWithAliases(IndexName("test-index4"), Set(IndexName("test-alias")))
            )
          ),
          found = Set(IndexName("test-index1"), IndexName("test-index2"))
        )
      }
      "cross cluster index is used together with local index" in {
        assertMatchRule(
          configured = NonEmptySet.of(index("odd:test1*"), index("local*")),
          requestIndices = Set(IndexName("local_index*"), IndexName("odd:test1_index*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(IndexName("local_index1"), Set.empty),
              IndexWithAliases(IndexName("local_index2"), Set.empty),
              IndexWithAliases(IndexName("other"), Set.empty)
            )
          ),
          found = Set(
            IndexName("local_index1"),
            IndexName("local_index2"),
            IndexName("odd:test1_index*")
          )
        )
      }
    }
    "not match" when {
      "no index passed, one is configured, no real indices" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(index("test")),
          requestIndices = Set.empty
        )
      }
      "'_all' passed, one is configured, no real indices" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(index("test")),
          requestIndices = Set(IndexName("_all"))
        )
      }
      "'*' passed, one is configured, no real indices" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(index("test")),
          requestIndices = Set(IndexName("*"))
        )
      }
      "one full name index passed, different one full name index configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(index("test1")),
          requestIndices = Set(IndexName("test2"))
        )
      }
      "one wildcard index passed, non-matching index with full name configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(index("test1")),
          requestIndices = Set(IndexName("*2"))
        )
      }
      "one full name index passed, non-matching index with wildcard configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(index("*1")),
          requestIndices = Set(IndexName("test2"))
        )
      }
      "two full name indexes passed, different two full name indexes configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(index("test1"), index("test2")),
          requestIndices = Set(IndexName("test4"), IndexName("test3"))
        )
      }
      "two wildcard indexes passed, non-matching two full name indexes configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(index("test1"), index("test2")),
          requestIndices = Set(IndexName("*4"), IndexName("*3"))
        )
      }
      "two full name indexes passed, non-matching two wildcard indexes configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(index("*1"), index("*2")),
          requestIndices = Set(IndexName("test4"), IndexName("test3"))
        )
      }
      "one full name alias passed, full name index with no alias configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(index("test-index")),
          requestIndices = Set(IndexName("test-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(IndexName("test-index"), Set.empty),
              IndexWithAliases(IndexName("test-index2"), Set(IndexName("test-alias")))
            )
          )
        )
      }
      "wildcard alias passed, full name index with no alias configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(index("test-index")),
          requestIndices = Set(IndexName("*-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(IndexName("test-index"), Set.empty),
              IndexWithAliases(IndexName("test-index2"), Set(IndexName("test-alias")))
            )
          )
        )
      }
      "full name index passed, index alias configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(index("test12-alias")),
          requestIndices = Set(IndexName("test-index1")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(IndexName("test-index1"), Set(IndexName("test12-alias"))),
              IndexWithAliases(IndexName("test-index2"), Set(IndexName("test12-alias"))),
              IndexWithAliases(IndexName("test-index3"), Set(IndexName("test34-alias"))),
              IndexWithAliases(IndexName("test-index4"), Set(IndexName("test34-alias")))
            )
          )
        )
      }
    }
  }

  "An IndicesRule for template context" when {
    "getting legacy template request is sent" should {
      "match" when {
        "template doesn't exist" in {
          assertMatchRule2(
            configured = NonEmptySet.of(index("*")),
            templateOperation = LegacyTemplateGetting(NonEmptyList.of(TemplateNamePattern("t*")))
          )
        }
      }
    }
    "adding legacy template request is sent" should {
      "match" when {
        "template with given name doesn't exit" when {
          "rule allows access to all indices" in {
            assertMatchRule2(
              configured = NonEmptySet.of(index("*")),
              templateOperation = LegacyTemplateAdding(
                name = TemplateName("t1"),
                patterns = UniqueNonEmptyList.of(IndexName("test1"))
              )
            )
          }
          "rule allows access to index name which is used in template's pattern list" in {
            assertMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              templateOperation = LegacyTemplateAdding(
                name = TemplateName("t1"),
                patterns = UniqueNonEmptyList.of(IndexName("test1"))
              )
            )
          }
          "rule allows access to index name with wildcard which is a superset of the pattern in template's pattern list" in {
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              templateOperation = LegacyTemplateAdding(
                name = TemplateName("t1"),
                patterns = UniqueNonEmptyList.of(IndexName("test1*"))
              )
            )
          }
          "rule allows access to index name with wildcard which matches both patterns in template's pattern list" in {
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              templateOperation = LegacyTemplateAdding(
                name = TemplateName("t1"),
                patterns = UniqueNonEmptyList.of(IndexName("test1"), IndexName("test2"))
              )
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to all indices" in {
            val existingTemplate = LegacyIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexName("test1"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("*")),
              modifyRequestContext = addExistingTemplates(existingTemplate),
              templateOperation = LegacyTemplateAdding(
                name = existingTemplate.name,
                patterns = UniqueNonEmptyList.of(IndexName("test2"))
              )
            )
          }
          "rule allows access to index name which is used in existing template's pattern list" in {
            val existingTemplate = LegacyIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexName("test1"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              modifyRequestContext = addExistingTemplates(existingTemplate),
              templateOperation = LegacyTemplateAdding(existingTemplate.name, existingTemplate.patterns)
            )
          }
          "rule allows access to index name with wildcard which is a superset of the patten in existing template's pattern list" in {
            val existingTemplate = LegacyIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexName("test1*"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              modifyRequestContext = addExistingTemplates(existingTemplate),
              templateOperation = LegacyTemplateAdding(
                name = existingTemplate.name,
                patterns = UniqueNonEmptyList.of(IndexName("test2*"))
              )
            )
          }
          "rule allows access to index name with wildcard which matches both patterns in existing template's pattern list" in {
            val existingTemplate = LegacyIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexName("test1"), IndexName("test2"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              modifyRequestContext = addExistingTemplates(existingTemplate),
              templateOperation = LegacyTemplateAdding(
                name = existingTemplate.name,
                patterns = UniqueNonEmptyList.of(IndexName("test1"), IndexName("test2"), IndexName("test3"))
              )
            )
          }
        }
      }
      "not match" when {
        "template with given name doesn't exist" when {
          "rule allows access to index name which is not used in template's pattern list" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              templateOperation = LegacyTemplateAdding(
                name = TemplateName("t1"),
                patterns = UniqueNonEmptyList.of(IndexName("test2"))
              )
            )
          }
          "rule allows access to index name which matches the pattern in template's pattern list" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              templateOperation = LegacyTemplateAdding(
                name = TemplateName("t1"),
                patterns = UniqueNonEmptyList.of(IndexName("test*"))
              )
            )
          }
          "rule allows access to index name with wildcard which is a subset of the pattern in template's pattern list" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1*")),
              templateOperation = LegacyTemplateAdding(
                name = TemplateName("t1"),
                patterns = UniqueNonEmptyList.of(IndexName("test*"))
              )
            )
          }
          "rule allows access ot index name with wildcard which matches only one pattern in template's pattern list" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              templateOperation = LegacyTemplateAdding(
                name = TemplateName("t1"),
                patterns = UniqueNonEmptyList.of(IndexName("test1*"), IndexName("index1*"))
              )
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to index name which is not used in existing template's pattern list" in {
            val existingTemplate = LegacyIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexName("test2"))
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              modifyRequestContext = addExistingTemplates(existingTemplate),
              templateOperation = LegacyTemplateAdding(
                name = existingTemplate.name,
                patterns = UniqueNonEmptyList.of(IndexName("test1"))
              )
            )
          }
          "rule allows access to index name which matches the pattern in existing template's pattern list" in {
            val existingTemplate = LegacyIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexName("test*"))
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              modifyRequestContext = addExistingTemplates(existingTemplate),
              templateOperation = LegacyTemplateAdding(
                name = existingTemplate.name,
                patterns = UniqueNonEmptyList.of(IndexName("test1"))
              )
            )
          }
          "rule allows access to index name with wildcard which is a subset of the pattern in existing template's pattern list" in {
            val existingTemplate = LegacyIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexName("test*"))
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1*")),
              modifyRequestContext = addExistingTemplates(existingTemplate),
              templateOperation = LegacyTemplateAdding(
                name = existingTemplate.name,
                patterns = UniqueNonEmptyList.of(IndexName("test*"))
              )
            )
          }
          "rule allows access ot index name with wildcard which matches only one pattern in existing template's pattern list" in {
            val existingTemplate = LegacyIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexName("test1*"), IndexName("index1*"))
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              modifyRequestContext = addExistingTemplates(existingTemplate),
              templateOperation = LegacyTemplateAdding(
                name = existingTemplate.name,
                patterns = UniqueNonEmptyList.of(IndexName("test*"))
              )
            )
          }
        }
      }
    }
    "deleting legacy template request is sent" should {
      "match" when {
        "template with given name doesn't exist" when {
          "rule allows access to all indices" in {
            assertMatchRule2(
              configured = NonEmptySet.of(index("*")),
              templateOperation = LegacyTemplateDeleting(NonEmptyList.of(TemplateNamePattern("t*"))),
              templateOperationAfterProcessing = LegacyTemplateDeleting(NonEmptyList.of(TemplateNamePattern("t*_ROR_0000000000"))),
              modifyRequestContext = noModifications
            )
          }
          "rule allows access to specific index" in {
            assertMatchRule2(
              configured = NonEmptySet.of(index("index1")),
              templateOperation = LegacyTemplateDeleting(NonEmptyList.of(TemplateNamePattern("t*"))),
              templateOperationAfterProcessing = LegacyTemplateDeleting(NonEmptyList.of(TemplateNamePattern("t*_ROR_0000000000"))),
              modifyRequestContext = noModifications
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to all indices" in {
            val existingTemplate1 = LegacyIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexName("index1"))
            )
            val existingTemplate2 = LegacyIndexTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(IndexName("index1"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("*")),
              templateOperation = LegacyTemplateDeleting(NonEmptyList.of(TemplateNamePattern("t*"))),
              templateOperationAfterProcessing = LegacyTemplateDeleting(NonEmptyList.of(TemplateNamePattern("t*"))),
              modifyRequestContext = addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "all requested existing templates have only allowed indices" in {
            val existingTemplate1 = LegacyIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexName("index1"), IndexName("index2"))
            )
            val existingTemplate2 = LegacyIndexTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(IndexName("index3"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("index1"), index("index2")),
              templateOperation = LegacyTemplateDeleting(NonEmptyList.of(TemplateNamePattern("t1"))),
              templateOperationAfterProcessing = LegacyTemplateDeleting(NonEmptyList.of(TemplateNamePattern("t1"))),
              modifyRequestContext = addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "all requested existing templates have only allowed indices patterns" in {
            val existingTemplate1 = LegacyIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexName("a1*"), IndexName("a2*"))
            )
            val existingTemplate2 = LegacyIndexTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(IndexName("b*"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("a*")),
              templateOperation = LegacyTemplateDeleting(NonEmptyList.of(TemplateNamePattern("t*"))),
              templateOperationAfterProcessing = LegacyTemplateDeleting(NonEmptyList.of(TemplateNamePattern("t*"))),
              modifyRequestContext = addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
        }
      }
      "not match" when {
        "template with given name exists" when {
          "one of existing requested templates has index which is forbidden" in {
            val existingTemplate1 = LegacyIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexName("index1"))
            )
            val existingTemplate2 = LegacyIndexTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(IndexName("index1"), IndexName("index2"))
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("index1")),
              templateOperation = LegacyTemplateDeleting(NonEmptyList.of(TemplateNamePattern("t*"))),
              templateOperationAfterProcessing = LegacyTemplateDeleting(NonEmptyList.of(TemplateNamePattern("t*"))),
              modifyRequestContext = addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "one of existing requested templates has index pattern which is forbidden" in {
            val existingTemplate1 = LegacyIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexName("index1*"))
            )
            val existingTemplate2 = LegacyIndexTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(IndexName("index1*"), IndexName("index2*"))
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("index1*")),
              templateOperation = LegacyTemplateDeleting(NonEmptyList.of(TemplateNamePattern("t*"))),
              templateOperationAfterProcessing = LegacyTemplateDeleting(NonEmptyList.of(TemplateNamePattern("t*"))),
              modifyRequestContext = addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "requested existing template has pattern which values form a superset of set of configured index pattern values" in {
            val existingTemplate1 = LegacyIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexName("i*1"))
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("index*")),
              templateOperation = LegacyTemplateDeleting(NonEmptyList.of(TemplateNamePattern("t*"))),
              templateOperationAfterProcessing = LegacyTemplateDeleting(NonEmptyList.of(TemplateNamePattern("t*"))),
              modifyRequestContext = addExistingTemplates(existingTemplate1)
            )
          }
        }
      }
    }
  }

  private def assertMatchRule(configured: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                              requestIndices: Set[IndexName],
                              modifyRequestContext: MockGeneralIndexRequestContext => MockGeneralIndexRequestContext = identity,
                              found: Set[IndexName] = Set.empty) =
    assertRule(configured, requestIndices, isMatched = true, modifyRequestContext, found)

  private def assertNotMatchRule(configured: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                                 requestIndices: Set[IndexName],
                                 modifyRequestContext: MockGeneralIndexRequestContext => MockGeneralIndexRequestContext = identity) =
    assertRule(configured, requestIndices, isMatched = false, modifyRequestContext, Set.empty)

  private def assertRule(configuredValues: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                         requestIndices: Set[IndexName],
                         isMatched: Boolean,
                         modifyRequestContext: MockGeneralIndexRequestContext => MockGeneralIndexRequestContext,
                         found: Set[IndexName]) = {
    val rule = createIndicesRule(configuredValues)
    val requestContext = modifyRequestContext apply MockRequestContext.indices
      .copy(
        filteredIndices = requestIndices,
        action = Action("indices:data/read/search"),
        isReadOnlyRequest = true,
        hasRemoteClusters = true,
        allIndicesAndAliases = Set(
          IndexWithAliases(IndexName("test1"), Set.empty),
          IndexWithAliases(IndexName("test2"), Set.empty),
          IndexWithAliases(IndexName("test3"), Set.empty),
          IndexWithAliases(IndexName("test4"), Set.empty),
          IndexWithAliases(IndexName("test5"), Set.empty)
        )
      )
    val blockContext = GeneralIndexRequestBlockContext(
      requestContext,
      UserMetadata.from(requestContext),
      Set.empty,
      List.empty,
      requestIndices,
      Set.empty
    )
    rule.check(blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(GeneralIndexRequestBlockContext(
        requestContext,
        UserMetadata.from(requestContext),
        Set.empty,
        List.empty,
        found,
        configuredValues
          .toNonEmptyList.toList
          .collect { case a: AlreadyResolved[IndexName] => a }
          .flatMap(_.value.toList)
          .toSet
      ))
      else Rejected(Some(Cause.IndexNotFound))
    }
  }

  private def assertMatchRule2(configured: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                               templateOperation: TemplateOperation,
                               modifyRequestContext: MockTemplateRequestContext => MockTemplateRequestContext = identity): Assertion =
    assertMatchRule2(configured, templateOperation, templateOperation, modifyRequestContext)

  private def assertMatchRule2(configured: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                               templateOperation: TemplateOperation,
                               templateOperationAfterProcessing: TemplateOperation,
                               modifyRequestContext: MockTemplateRequestContext => MockTemplateRequestContext): Assertion =
    assertRule2(configured, templateOperation, isMatched = true, modifyRequestContext, templateOperationAfterProcessing)

  private def assertNotMatchRule2(configured: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                                  templateOperation: TemplateOperation,
                                  modifyRequestContext: MockTemplateRequestContext => MockTemplateRequestContext = identity): Assertion =
    assertNotMatchRule2(configured, templateOperation, templateOperation, modifyRequestContext)

  private def assertNotMatchRule2(configured: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                                  templateOperation: TemplateOperation,
                                  templateOperationAfterProcessing: TemplateOperation,
                                  modifyRequestContext: MockTemplateRequestContext => MockTemplateRequestContext): Assertion =
    assertRule2(configured, templateOperation, isMatched = false, modifyRequestContext, templateOperationAfterProcessing)

  private def assertRule2(configuredValues: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                          requestedTemplateOperation: TemplateOperation,
                          isMatched: Boolean,
                          modifyRequestContext: MockTemplateRequestContext => MockTemplateRequestContext,
                          templateOperationAfterProcessing: TemplateOperation) = {
    val rule = createIndicesRule(configuredValues)
    val requestContext = modifyRequestContext apply MockRequestContext.template(requestedTemplateOperation)
    val emptyUserMetadata = UserMetadata.from(requestContext)
    val blockContext = TemplateRequestBlockContext(
      requestContext,
      emptyUserMetadata,
      Set.empty,
      List.empty,
      requestedTemplateOperation,
      identity
    )
    val ruleResult = rule.check(blockContext).runSyncStep.right.get
    if(isMatched) {
      ruleResult should matchPattern {
        case Fulfilled(TemplateRequestBlockContext(rc, metadata, headers, Nil, operation, _))
          if rc == requestContext
            && metadata == emptyUserMetadata
            && headers.isEmpty
            && operation == templateOperationAfterProcessing =>
      }
    } else {
      ruleResult shouldBe Rejected()
    }
  }

  private def createIndicesRule(configuredValues: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]]) = {
    new IndicesRule(
      settings = IndicesRule.Settings(configuredValues, mustInvolveIndices = false),
      identifierGenerator = (_: Refined[Int, Positive]) => "0000000000"
    )
  }

  private def index(value: NonEmptyString): RuntimeMultiResolvableVariable[IndexName] = {
    RuntimeResolvableVariableCreator
      .createMultiResolvableVariableFrom(value)(AlwaysRightConvertible.from(IndexName.apply))
      .right
      .getOrElse(throw new IllegalStateException(s"Cannot create IndexName Value from $value"))
  }

  private def addExistingTemplates(template: Template, otherTemplates: Template*): MockTemplateRequestContext => MockTemplateRequestContext = requestContext => {
    requestContext.copy(allTemplates = requestContext.allTemplates + template ++ otherTemplates.toSet)
  }

  private def noModifications: MockTemplateRequestContext => MockTemplateRequestContext = identity
}
