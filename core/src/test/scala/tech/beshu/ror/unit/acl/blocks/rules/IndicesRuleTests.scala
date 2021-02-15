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

import cats.data.{NonEmptyList, NonEmptySet}
import com.softwaremill.sttp.Method
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Assertion, Succeeded, WordSpec}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{FilterableMultiRequestBlockContext, GeneralIndexRequestBlockContext, TemplateRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.IndicesRule
import tech.beshu.ror.accesscontrol.blocks.rules.utils.RandomBasedUniqueIdentifierGenerator
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.domain.Template.{ComponentTemplate, IndexTemplate, LegacyTemplate}
import tech.beshu.ror.accesscontrol.domain.TemplateOperation._
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.orders.indexOrder
import tech.beshu.ror.mocks.{MockFilterableMultiRequestContext, MockGeneralIndexRequestContext, MockRequestContext, MockTemplateRequestContext}
import tech.beshu.ror.utils.TestsUtils._
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
      "multi filterable request tries to fetch data for allowed and not allowed index" in {
        assertMatchRuleMultiIndexRequest(
          configured = NonEmptySet.of(index("test1")),
          indexPacks = Indices.Found(Set(IndexName("test1".nonempty), IndexName("test2".nonempty))) :: Nil,
          allowed = Indices.Found(Set(IndexName("test1".nonempty))) :: Nil
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

  "An IndicesRule for legacy template context" when {
    "getting legacy template request is sent" should {
      "match" when {
        "template doesn't exist" in {
          val gettingTemplateOperation = GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
          assertMatchRule2(
            configured = NonEmptySet.of(index("test*")),
            requestContext = MockRequestContext.template(gettingTemplateOperation),
            templateOperationAfterProcessing = gettingTemplateOperation
          )
        }
        "template exists" when {
          "rule allows access to all indices" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2")),
              aliases = Set.empty
            )
            val gettingTemplateOperation = GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertMatchRule2(
              configured = NonEmptySet.of(index("*")),
              requestContext = MockRequestContext
                .template(gettingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = gettingTemplateOperation,
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate)) should be (Set(existingTemplate))
            )
          }
          "rule allows access not to all indices, but there is at least one matching template with at least one index pattern allowed and no alias allowed" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1*"), IndexPattern("test2*")),
              aliases = Set.empty
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test3*"), IndexPattern("test4*")),
              aliases = Set.empty
            )
            val existingTemplate3 = LegacyTemplate(
              name = TemplateName("a3"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1*"), IndexPattern("test2*")),
              aliases = Set.empty
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("t*1*")),
              requestContext = MockRequestContext
                .template(GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2, existingTemplate3),
              templateOperationAfterProcessing =
                GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t1"))),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate1)) should be (Set(
                  LegacyTemplate(
                    name = TemplateName("t1"),
                    patterns = UniqueNonEmptyList.of(IndexPattern("test1*")),
                    aliases = Set.empty
                  )
                ))
            )
          }
          "rule allows access not to all indices, but there is at least one matching template with at least one index pattern allowed and one alias allowed" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1*"), IndexPattern("test2*")),
              aliases = Set(IndexName("test1_alias"), IndexName("test2_alias"))
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test3*"), IndexPattern("test4*")),
              aliases = Set.empty
            )
            val existingTemplate3 = LegacyTemplate(
              name = TemplateName("a3"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1*"), IndexPattern("test2*")),
              aliases = Set.empty
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("t*1*")),
              requestContext = MockRequestContext
                .template(GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2, existingTemplate3),
              templateOperationAfterProcessing =
                GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t1"))),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate1)) should be (Set(
                  LegacyTemplate(
                    name = TemplateName("t1"),
                    patterns = UniqueNonEmptyList.of(IndexPattern("test1*")),
                    aliases = Set(IndexName("test1_alias"))
                  )
                ))
            )
          }
        }
        "not match" when {
          "template exists" when {
            "no template is matched" in {
              val existingTemplate1 = LegacyTemplate(
                name = TemplateName("t1"),
                patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2")),
                aliases = Set.empty
              )
              val existingTemplate2 = LegacyTemplate(
                name = TemplateName("t2"),
                patterns = UniqueNonEmptyList.of(IndexPattern("test3")),
                aliases = Set.empty
              )
              val gettingTemplateOperation = GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
              assertNotMatchRule2(
                configured = NonEmptySet.of(index("test3")),
                requestContext = MockRequestContext
                  .template(gettingTemplateOperation)
                  .addExistingTemplates(existingTemplate1, existingTemplate2),
                specialCause = Some(Cause.TemplateNotFound)
              )
            }
          }
        }
      }
    }
    "adding legacy template request is sent" should {
      "match" when {
        "template with given name doesn't exit" when {
          "rule allows access to all indices" in {
            val addingTemplateOperation = AddingLegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1")),
              aliases = Set.empty
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set.empty) should be (Set.empty)
            )
          }
          "rule allows access to index name which is used in template's pattern list" in {
            val addingTemplateOperation = AddingLegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1")),
              aliases = Set.empty
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which is a superset of the pattern in template's pattern list" in {
            val addingTemplateOperation = AddingLegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1*")),
              aliases = Set.empty
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which matches both patterns in template's pattern list" in {
            val addingTemplateOperation = AddingLegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2")),
              aliases = Set.empty
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which matches pattern in template's pattern list and all aliases (without index placeholder)" in {
            val addingTemplateOperation = AddingLegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2")),
              aliases = Set(IndexName("test1_alias"), IndexName("test2_alias"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which matches pattern in template's pattern list and all aliases (with index placeholder)" in {
            val addingTemplateOperation = AddingLegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2")),
              aliases = Set(IndexName("{index}_alias"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to all indices" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingLegacyTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(IndexPattern("test2")),
              aliases = Set.empty
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name which is used in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingLegacyTemplate(existingTemplate.name, existingTemplate.patterns, Set.empty)
            assertMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which is a superset of the patten in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1*")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingLegacyTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(IndexPattern("test2*")),
              aliases = Set.empty
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which matches both patterns in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingLegacyTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2"), IndexPattern("test3")),
              aliases = Set.empty
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which matches pattern in existing template's pattern list and all aliases (without index placeholder)" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingLegacyTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2"), IndexPattern("test3")),
              aliases = Set(IndexName("test1_alias"), IndexName("test2_alias"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which matches pattern in existing template's pattern list and all aliases (with index placeholder)" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingLegacyTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2"), IndexPattern("test3")),
              aliases = Set(IndexName("{index}_alias"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
        }
      }
      "not match" when {
        "template with given name doesn't exist" when {
          "rule allows access to index name which is not used in template's pattern list" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(IndexPattern("test2")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access to index name which matches the pattern in template's pattern list" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(IndexPattern("test*")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access to index name with wildcard which is a subset of the pattern in template's pattern list" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(IndexPattern("test*")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access ot index name with wildcard which matches only one pattern in template's pattern list" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(IndexPattern("test1*"), IndexPattern("index1*")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (without index placeholder)" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(IndexPattern("test1*")),
                  aliases = Set(IndexName("test1_alias"), IndexName("alias_test1"))
                ))
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (with index placeholder)" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(IndexPattern("test1*")),
                  aliases = Set(IndexName("{index}_alias"), IndexName("alias_{index}"))
                ))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to index name which is not used in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test2")),
              aliases = Set.empty
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(IndexPattern("test1")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access to index name which matches the pattern in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test*")),
              aliases = Set.empty
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(IndexPattern("test1")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access to index name with wildcard which is a subset of the pattern in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test*")),
              aliases = Set.empty
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(IndexPattern("test*")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access ot index name with wildcard which matches only one pattern in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1*"), IndexPattern("index1*")),
              aliases = Set.empty
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(IndexPattern("test*")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (without index placeholder)" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1*"), IndexPattern("index1*")),
              aliases = Set.empty
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(IndexPattern("test1*")),
                  aliases = Set(IndexName("test1_alias"), IndexName("alias_test1"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (with index placeholder)" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1*"), IndexPattern("index1*")),
              aliases = Set.empty
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(IndexPattern("test1*")),
                  aliases = Set(IndexName("{index}_alias"), IndexName("alias_{index}"))
                ))
                .addExistingTemplates(existingTemplate)
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
              requestContext = MockRequestContext
                .template(DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*")))),
              templateOperationAfterProcessing =
                DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*_ROR_0000000000")))
            )
          }
          "rule allows access to specific index" in {
            assertMatchRule2(
              configured = NonEmptySet.of(index("index1")),
              requestContext = MockRequestContext
                .template(DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*")))),
              templateOperationAfterProcessing =
                DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*_ROR_0000000000")))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to all indices" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index1")),
              aliases = Set.empty
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index1")),
              aliases = Set.empty
            )
            val deletingTemplateOperation = DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRule2(
              configured = NonEmptySet.of(index("*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation
            )
          }
          "all requested existing templates have only allowed indices" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index1"), IndexPattern("index2")),
              aliases = Set.empty
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index3")),
              aliases = Set.empty
            )
            val deletingTemplateOperation = DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertMatchRule2(
              configured = NonEmptySet.of(index("index1"), index("index2")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation
            )
          }
          "all requested existing templates have only allowed indices patterns" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("a1*"), IndexPattern("a2*")),
              aliases = Set.empty
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("b*")),
              aliases = Set.empty
            )
            val deletingTemplateOperation = DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRule2(
              configured = NonEmptySet.of(index("a*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation
            )
          }
          "all requested existing templates have only allowed indices patterns and aliases" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("a1*"), IndexPattern("a2*")),
              aliases = Set(IndexName("alias"))
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("a*")),
              aliases = Set(IndexName("balias"))
            )
            val deletingTemplateOperation = DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRule2(
              configured = NonEmptySet.of(index("a*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation
            )
          }
        }
      }
      "not match" when {
        "template with given name exists" when {
          "one of existing requested templates has index which is forbidden" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index1")),
              aliases = Set.empty
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index1"), IndexPattern("index2")),
              aliases = Set.empty
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("index1")),
              requestContext = MockRequestContext
                .template(DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "one of existing requested templates has index pattern which is forbidden" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index1*")),
              aliases = Set.empty
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index1*"), IndexPattern("index2*")),
              aliases = Set.empty
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("index1*")),
              requestContext = MockRequestContext
                .template(DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "one of existing requested templates has alias which is forbidden" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index11*")),
              aliases = Set(IndexName("index11_alias"))
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index12*")),
              aliases = Set(IndexName("alias"))
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("index1*")),
              requestContext = MockRequestContext
                .template(DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "requested existing template has pattern which values form a superset of set of configured index pattern values" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("i*1")),
              aliases = Set.empty
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("index*")),
              requestContext = MockRequestContext
                .template(DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1)
            )
          }
        }
      }
    }
  }

  "An IndicesRule for index template context" when {
    "getting index template request is sent" should {
      "match" when {
        "template doesn't exist" in {
          val gettingTemplateOperation = GettingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
          assertMatchRule2(
            configured = NonEmptySet.of(index("test*")),
            requestContext = MockRequestContext.template(gettingTemplateOperation),
            templateOperationAfterProcessing = gettingTemplateOperation
          )
        }
        "template exists" when {
          "rule allows access to all indices" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2")),
              aliases = Set(IndexName("alias1"))
            )
            val gettingTemplateOperation = GettingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertMatchRule2(
              configured = NonEmptySet.of(index("*")),
              requestContext = MockRequestContext
                .template(gettingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = gettingTemplateOperation,
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate)) should be (Set(existingTemplate))
            )
          }
          "rule allows access not to all indices, but there is at least one matching template with at least one index pattern allowed and no alias allowed" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1*"), IndexPattern("test2*")),
              aliases = Set.empty
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test3*"), IndexPattern("test4*")),
              aliases = Set.empty
            )
            val existingTemplate3 = IndexTemplate(
              name = TemplateName("a3"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1*"), IndexPattern("test2*")),
              aliases = Set.empty
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("t*1*")),
              requestContext = MockRequestContext
                .template(GettingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2, existingTemplate3),
              templateOperationAfterProcessing =
                GettingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t1"))),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate1)) should be (Set(
                  IndexTemplate(
                    name = TemplateName("t1"),
                    patterns = UniqueNonEmptyList.of(IndexPattern("test1*")),
                    aliases = Set.empty
                  )
                ))
            )
          }
          "rule allows access not to all indices, but there is at least one matching template with at least one index pattern allowed and one alias allowed" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1*"), IndexPattern("test2*")),
              aliases = Set(IndexName("test1_alias"), IndexName("test2_alias"))
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test3*"), IndexPattern("test4*")),
              aliases = Set.empty
            )
            val existingTemplate3 = IndexTemplate(
              name = TemplateName("a3"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1*"), IndexPattern("test2*")),
              aliases = Set.empty
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("t*1*")),
              requestContext = MockRequestContext
                .template(GettingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2, existingTemplate3),
              templateOperationAfterProcessing =
                GettingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t1"))),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate1)) should be (Set(
                  IndexTemplate(
                    name = TemplateName("t1"),
                    patterns = UniqueNonEmptyList.of(IndexPattern("test1*")),
                    aliases = Set(IndexName("test1_alias"))
                  )
                ))
            )
          }
        }
      }
      "not match" when {
        "template exists" when {
          "no template is matched (because of forbidden indices patterns)" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2")),
              aliases = Set.empty
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test3")),
              aliases = Set.empty
            )
            val gettingTemplateOperation = GettingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test3")),
              requestContext = MockRequestContext
                .template(gettingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
        }
      }
    }
    "adding index template request is sent" should {
      "match" when {
        "template with given name doesn't exit" when {
          "rule allows access to all indices" in {
            val addingTemplateOperation = AddingIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1")),
              aliases = Set(IndexName("alias1"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set.empty) should be (Set.empty)
            )
          }
          "rule allows access to index name which is used in template's pattern list" in {
            val addingTemplateOperation = AddingIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1")),
              aliases = Set.empty
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which is a superset of the pattern in template's pattern list" in {
            val addingTemplateOperation = AddingIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1*")),
              aliases = Set.empty
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which matches both patterns in template's pattern list" in {
            val addingTemplateOperation = AddingIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2")),
              aliases = Set.empty
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which matches pattern in template's pattern list and all aliases (without index placeholder)" in {
            val addingTemplateOperation = AddingIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2")),
              aliases = Set(IndexName("test1_alias"), IndexName("test2_alias"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which matches pattern in template's pattern list and all aliases (with index placeholder)" in {
            val addingTemplateOperation = AddingIndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2")),
              aliases = Set(IndexName("{index}_alias"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to all indices" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingIndexTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(IndexPattern("test2")),
              aliases = Set.empty
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name which is used in existing template's pattern list" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingIndexTemplate(existingTemplate.name, existingTemplate.patterns, existingTemplate.aliases)
            assertMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which is a superset of the patten in existing template's pattern list" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1*")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingIndexTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(IndexPattern("test2*")),
              aliases = Set.empty
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which matches both patterns in existing template's pattern list" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingIndexTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2"), IndexPattern("test3")),
              aliases = Set.empty
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which matches pattern in existing template's pattern list and all aliases (without index placeholder)" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingIndexTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2"), IndexPattern("test3")),
              aliases = Set(IndexName("test1_alias"), IndexName("test2_alias"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which matches pattern in existing template's pattern list and all aliases (with index placeholder)" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingIndexTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(IndexPattern("test1"), IndexPattern("test2"), IndexPattern("test3")),
              aliases = Set(IndexName("{index}_alias"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
        }
      }
      "not match" when {
        "template with given name doesn't exist" when {
          "rule allows access to index name which is not used in template's pattern list" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(IndexPattern("test2")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access to index name which matches the pattern in template's pattern list" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(IndexPattern("test*")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access to index name with wildcard which is a subset of the pattern in template's pattern list" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1*")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(IndexPattern("test*")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access ot index name with wildcard which matches only one pattern in template's pattern list" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(IndexPattern("test1*"), IndexPattern("index1*")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (without index placeholder)" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(IndexPattern("test1*")),
                  aliases = Set(IndexName("test1_alias"), IndexName("alias_test1"))
                ))
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (with index placeholder)" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(IndexPattern("test1*")),
                  aliases = Set(IndexName("{index}_alias"), IndexName("alias_{index}"))
                ))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to index name which is not used in existing template's pattern list" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test2")),
              aliases = Set.empty
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(IndexPattern("test1")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access to index name which matches the pattern in existing template's pattern list" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test*")),
              aliases = Set.empty
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(IndexPattern("test1")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access to index name with wildcard which is a subset of the pattern in existing template's pattern list" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test*")),
              aliases = Set.empty
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1*")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(IndexPattern("test*")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access ot index name with wildcard which matches only one pattern in existing template's pattern list" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1*"), IndexPattern("index1*")),
              aliases = Set.empty
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(IndexPattern("test*")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (without index placeholder)" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1*"), IndexPattern("index1*")),
              aliases = Set.empty
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(IndexPattern("test1*")),
                  aliases = Set(IndexName("test1_alias"), IndexName("alias_test1"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (with index placeholder)" in {
            val existingTemplate = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("test1*"), IndexPattern("index1*")),
              aliases = Set.empty
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(AddingIndexTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(IndexPattern("test1*")),
                  aliases = Set(IndexName("{index}_alias"), IndexName("alias_{index}"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
        }
      }
    }
    "deleting index template request is sent" should {
      "match" when {
        "template with given name doesn't exist" when {
          "rule allows access to all indices" in {
            assertMatchRule2(
              configured = NonEmptySet.of(index("*")),
              requestContext = MockRequestContext
                .template(DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*")))),
              templateOperationAfterProcessing =
                DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*_ROR_0000000000")))
            )
          }
          "rule allows access to specific index" in {
            assertMatchRule2(
              configured = NonEmptySet.of(index("index1")),
              requestContext = MockRequestContext
                .template(DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*")))),
              templateOperationAfterProcessing =
                DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*_ROR_0000000000")))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to all indices" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index1")),
              aliases = Set(IndexName("alias"))
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index1")),
              aliases = Set.empty
            )
            val deletingTemplateOperation = DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRule2(
              configured = NonEmptySet.of(index("*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation
            )
          }
          "all requested existing templates have only allowed indices" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index1"), IndexPattern("index2")),
              aliases = Set.empty
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index3")),
              aliases = Set.empty
            )
            val deletingTemplateOperation = DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertMatchRule2(
              configured = NonEmptySet.of(index("index1"), index("index2")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation
            )
          }
          "all requested existing templates have only allowed indices patterns" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("a1*"), IndexPattern("a2*")),
              aliases = Set.empty
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("b*")),
              aliases = Set.empty
            )
            val deletingTemplateOperation = DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRule2(
              configured = NonEmptySet.of(index("a*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation
            )
          }
          "all requested existing templates have only allowed indices patterns and aliases" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("a1*"), IndexPattern("a2*")),
              aliases = Set(IndexName("alias"))
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("a*")),
              aliases = Set(IndexName("balias"))
            )
            val deletingTemplateOperation = DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRule2(
              configured = NonEmptySet.of(index("a*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation
            )
          }
        }
      }
      "not match" when {
        "template with given name exists" when {
          "one of existing requested templates has index which is forbidden" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index1")),
              aliases = Set.empty
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index1"), IndexPattern("index2")),
              aliases = Set.empty
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("index1")),
              requestContext = MockRequestContext
                .template(DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "one of existing requested templates has index pattern which is forbidden" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index1*")),
              aliases = Set.empty
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index1*"), IndexPattern("index2*")),
              aliases = Set.empty
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("index1*")),
              requestContext = MockRequestContext
                .template(DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "one of existing requested templates has alias which is forbidden" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index11*")),
              aliases = Set(IndexName("index11_alias"))
            )
            val existingTemplate2 = IndexTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(IndexPattern("index12*")),
              aliases = Set(IndexName("alias"))
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("index1*")),
              requestContext = MockRequestContext
                .template(DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "requested existing template has pattern which values form a superset of set of configured index pattern values" in {
            val existingTemplate1 = IndexTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(IndexPattern("i*1")),
              aliases = Set.empty
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("index*")),
              requestContext = MockRequestContext
                .template(DeletingIndexTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1)
            )
          }
        }
      }
    }
  }

  "An Indices Rule for component template context" when {
    "getting component template request is sent" should {
      "match" when {
        "template doesn't exist" in {
          val gettingTemplateOperation = GettingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
          assertMatchRule2(
            configured = NonEmptySet.of(index("test*")),
            requestContext = MockRequestContext.template(gettingTemplateOperation),
            templateOperationAfterProcessing = gettingTemplateOperation
          )
        }
        "template exists" when {
          "rule allows access to all indices" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("alias1"))
            )
            val gettingTemplateOperation = GettingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertMatchRule2(
              configured = NonEmptySet.of(index("*")),
              requestContext = MockRequestContext
                .template(gettingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = gettingTemplateOperation,
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate)) should be (Set(existingTemplate))
            )
          }
          "rule allows access not to all indices, but there is at least one alias allowed" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("test1_alias"), IndexName("test2_alias"))
            )
            val existingTemplate2 = ComponentTemplate(
              name = TemplateName("t2"),
              aliases = Set.empty
            )
            val existingTemplate3 = ComponentTemplate(
              name = TemplateName("d3"),
              aliases = Set.empty
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("t*1*")),
              requestContext = MockRequestContext
                .template(GettingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2, existingTemplate3),
              templateOperationAfterProcessing =
                GettingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t1"), TemplateNamePattern("t2"))),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate1, existingTemplate2)) should be (Set(
                  ComponentTemplate(
                    name = TemplateName("t1"),
                    aliases = Set(IndexName("test1_alias"))
                  ),
                  existingTemplate2
                ))
            )
          }
          "all aliases are forbidden" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("alias1"))
            )
            val gettingTemplateOperation = GettingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertMatchRule2(
              configured = NonEmptySet.of(index("index1")),
              requestContext = MockRequestContext
                .template(gettingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = gettingTemplateOperation,
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate)) should be (Set(
                  ComponentTemplate(
                    name = TemplateName("t1"),
                    aliases = Set.empty
                  )
                ))
            )
          }
        }
      }
    }
    "adding component template request is sent" should {
      "match" when {
        "template with given name doesn't exit" when {
          "rule allows access to all indices" in {
            val addingTemplateOperation = AddingComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("alias1"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name which is used in template's aliases list" in {
            val addingTemplateOperation = AddingComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("alias1"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("alias1")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which is a superset of the pattern in template's aliases list" in {
            val addingTemplateOperation = AddingComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("test1*"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which matches both patterns in template's aliases list" in {
            val addingTemplateOperation = AddingComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("test1"), IndexName("test2"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to all indices" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("test1"))
            )
            val addingTemplateOperation = AddingComponentTemplate(
              name = existingTemplate.name,
              aliases = Set(IndexName("test2"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name which is used in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("test1"))
            )
            val addingTemplateOperation = AddingComponentTemplate(existingTemplate.name, existingTemplate.aliases)
            assertMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which is a superset of the patten in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("test1*"))
            )
            val addingTemplateOperation = AddingComponentTemplate(
              name = existingTemplate.name,
              aliases = Set(IndexName("test2*"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
          "rule allows access to index name with wildcard which matches both patterns in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("test1"), IndexName("test2"))
            )
            val addingTemplateOperation = AddingComponentTemplate(
              name = existingTemplate.name,
              aliases = Set(IndexName("test1"), IndexName("test2"), IndexName("test3"))
            )
            assertMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation
            )
          }
        }
      }
      "not match" when {
        "template with given name doesn't exit" when {
          "rule allows access to index name which is not used in template's aliases list" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = TemplateName("t1"),
                  aliases = Set(IndexName("test2"))
                ))
            )
          }
          "rule allows access to index name which matches the pattern in template's aliases list" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = TemplateName("t1"),
                  aliases = Set(IndexName("test*"))
                ))
            )
          }
          "rule allows access to index name with wildcard which is a subset of the pattern in template's aliases list" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1*")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = TemplateName("t1"),
                  aliases = Set(IndexName("test*"))
                ))
            )
          }
          "rule allows access ot index name with wildcard which matches only one pattern in template's aliases list" in {
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = TemplateName("t1"),
                  aliases = Set(IndexName("test1*"), IndexName("index1*"))
                ))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to index name which is not used in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("test2"))
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = existingTemplate.name,
                  aliases = Set(IndexName("test1"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access to index name which matches the pattern in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("test*"))
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = existingTemplate.name,
                  aliases = Set(IndexName("test1"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access to index name with wildcard which is a subset of the pattern in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("test*"))
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test1*")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = existingTemplate.name,
                  aliases = Set(IndexName("test*"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access ot index name with wildcard which matches only one pattern in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("test1*"), IndexName("index1*"))
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("test*")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = existingTemplate.name,
                  aliases = Set(IndexName("test*"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
        }
      }
    }
    "deleting component template request is sent" should {
      "match" when {
        "template with given name doesn't exist" when {
          "rule allows access to all indices" in {
            assertMatchRule2(
              configured = NonEmptySet.of(index("*")),
              requestContext = MockRequestContext
                .template(DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*")))),
              templateOperationAfterProcessing =
                DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*_ROR_0000000000")))
            )
          }
          "rule allows access to specific index" in {
            assertMatchRule2(
              configured = NonEmptySet.of(index("index1")),
              requestContext = MockRequestContext
                .template(DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*")))),
              templateOperationAfterProcessing =
                DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*_ROR_0000000000")))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to all indices" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("index1"))
            )
            val existingTemplate2 = ComponentTemplate(
              name = TemplateName("s1"),
              aliases = Set(IndexName("index1"))
            )
            val deletingTemplateOperation = DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRule2(
              configured = NonEmptySet.of(index("*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation
            )
          }
          "all requested existing templates have only allowed aliases" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("index1"), IndexName("index2"))
            )
            val existingTemplate2 = ComponentTemplate(
              name = TemplateName("s1"),
              aliases = Set(IndexName("index3"))
            )
            val deletingTemplateOperation = DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertMatchRule2(
              configured = NonEmptySet.of(index("index1"), index("index2")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation
            )
          }
          "all requested existing templates have only allowed aliases patterns" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("a1*"), IndexName("a2*"))
            )
            val existingTemplate2 = ComponentTemplate(
              name = TemplateName("s1"),
              aliases = Set(IndexName("b*"))
            )
            val deletingTemplateOperation = DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRule2(
              configured = NonEmptySet.of(index("a*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation
            )
          }
        }
      }
      "not match" when {
        "template with given name exists" when {
          "one of existing requested templates has alias which is forbidden" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("index1"))
            )
            val existingTemplate2 = ComponentTemplate(
              name = TemplateName("t2"),
              aliases = Set(IndexName("index1"), IndexName("index2"))
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("index1")),
              requestContext = MockRequestContext
                .template(DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "one of existing requested templates has alias pattern which is forbidden" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("index1*"))
            )
            val existingTemplate2 = ComponentTemplate(
              name = TemplateName("t2"),
              aliases = Set(IndexName("index1*"), IndexName("index2*"))
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("index1*")),
              requestContext = MockRequestContext
                .template(DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "requested existing template has pattern which values form a superset of set of configured alias pattern values" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(IndexName("i*1"))
            )
            assertNotMatchRule2(
              configured = NonEmptySet.of(index("index*")),
              requestContext = MockRequestContext
                .template(DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1)
            )
          }
        }
      }
      "multi filterable request tries to fetch data for not allowed index" in {
        assertNotMatchRuleMultiIndexRequest(
          configured = NonEmptySet.of(index("test1")),
          indexPacks = Indices.Found(Set(IndexName("test2".nonempty))) :: Nil
        )
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

  private def assertMatchRuleMultiIndexRequest(configured: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                                               indexPacks: List[Indices],
                                               modifyRequestContext: MockFilterableMultiRequestContext => MockFilterableMultiRequestContext = identity,
                                               allowed: List[Indices]) = {
    assertRuleForMultiIndexRequest(configured, indexPacks, isMatched = true, modifyRequestContext, allowed)
  }

  private def assertNotMatchRuleMultiIndexRequest(configured: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                                                  indexPacks: List[Indices],
                                                  modifyRequestContext: MockFilterableMultiRequestContext => MockFilterableMultiRequestContext = identity) = {
    assertRuleForMultiIndexRequest(configured, indexPacks, isMatched = false, modifyRequestContext, List.empty)
  }

  private def assertRuleForMultiIndexRequest(configuredValues: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                                             indexPacks: List[Indices],
                                             isMatched: Boolean,
                                             modifyRequestContext: MockFilterableMultiRequestContext => MockFilterableMultiRequestContext,
                                             allowed: List[Indices]) = {
    val rule = new IndicesRule(
      settings = IndicesRule.Settings(configuredValues, mustInvolveIndices = false),
      identifierGenerator = RandomBasedUniqueIdentifierGenerator
    )
    val requestContext = modifyRequestContext apply MockRequestContext.filterableMulti
      .copy(
        indexPacks = indexPacks,
        action = Action("indices:data/read/mget"),
        isReadOnlyRequest = true,
        method = Method("POST"),
        allIndicesAndAliases = Set(
          IndexWithAliases(IndexName("test1".nonempty), Set.empty),
          IndexWithAliases(IndexName("test2".nonempty), Set.empty),
          IndexWithAliases(IndexName("test3".nonempty), Set.empty),
          IndexWithAliases(IndexName("test4".nonempty), Set.empty),
          IndexWithAliases(IndexName("test5".nonempty), Set.empty)
        )
      )
    val blockContext = FilterableMultiRequestBlockContext(
      requestContext,
      UserMetadata.from(requestContext),
      Set.empty,
      List.empty,
      indexPacks,
      None
    )
    rule.check(blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(FilterableMultiRequestBlockContext(
        requestContext,
        UserMetadata.from(requestContext),
        Set.empty,
        List.empty,
        allowed,
        None
      ))
      else Rejected(Some(Cause.IndexNotFound))
    }
  }

  private def assertMatchRule2(configured: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                               requestContext: MockTemplateRequestContext,
                               templateOperationAfterProcessing: TemplateOperation,
                               additionalAssertions: TemplateRequestBlockContext => Assertion = noTransformation): Assertion = {
    val rule = createIndicesRule(configured)
    val ruleResult = rule.check(requestContext.initialBlockContext).runSyncStep.right.get
    ruleResult should matchPattern {
      case Fulfilled(blockContext@TemplateRequestBlockContext(rc, metadata, headers, Nil, operation, _))
        if rc == requestContext
          && metadata == requestContext.initialBlockContext.userMetadata
          && headers.isEmpty
          && operation == templateOperationAfterProcessing
          && additionalAssertions(blockContext) == Succeeded =>
    }
  }

  private def assertNotMatchRule2(configured: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                                  requestContext: MockTemplateRequestContext,
                                  specialCause: Option[Cause] = None): Assertion = {
    val rule = createIndicesRule(configured)
    val ruleResult = rule.check(requestContext.initialBlockContext).runSyncStep.right.get
    ruleResult shouldBe Rejected(specialCause)
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

  private implicit class MockTemplateRequestContextOps(underlying: MockTemplateRequestContext) {
    def addExistingTemplates(template: Template, otherTemplates: Template*): MockTemplateRequestContext = {
      underlying.copy(allTemplates = underlying.allTemplates + template ++ otherTemplates.toSet)
    }
  }

  private def noTransformation(blockContext: TemplateRequestBlockContext) = {
    // we check here if sth else than identity was configured
    val controlTemplates: Set[Template] = Set(
      LegacyTemplate(TemplateName("whatever1"), UniqueNonEmptyList.of(IndexPattern("*")), Set(IndexName("alias"))),
      IndexTemplate(TemplateName("whatever2"), UniqueNonEmptyList.of(IndexPattern("*")), Set(IndexName("alias"))),
      ComponentTemplate(TemplateName("whatever3"), Set(IndexName("alias"))),
    )
    blockContext.responseTemplateTransformation(controlTemplates) should be(controlTemplates)
  }
}
