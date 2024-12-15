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
package tech.beshu.ror.unit.acl.blocks.rules.indices

import cats.data.{NonEmptyList, NonEmptySet}
import eu.timepit.refined.auto.*
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.domain.Template.LegacyTemplate
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.{AddingLegacyTemplate, DeletingLegacyTemplates, GettingLegacyTemplates}
import tech.beshu.ror.accesscontrol.domain.{TemplateName, TemplateNamePattern}
import tech.beshu.ror.accesscontrol.orders.custerIndexNameOrder
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.{clusterIndexName, indexPattern, requestedIndex, unsafeNes}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

private [indices] trait IndicesRuleLegacyTemplateTests {
  this: BaseIndicesRuleTests =>

  "An IndicesRule for legacy template context" when {
    "getting legacy template request is sent" should {
      "match" when {
        "template doesn't exist" in {
          val gettingTemplateOperation = GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
          assertMatchRuleForTemplateRequest(
            configured = NonEmptySet.of(indexNameVar("test*")),
            requestContext = MockRequestContext.template(gettingTemplateOperation),
            templateOperationAfterProcessing = gettingTemplateOperation,
            allAllowedIndices = Set(clusterIndexName("test*"))
          )
        }
        "template exists" when {
          "rule allows access to all indices" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set.empty
            )
            val gettingTemplateOperation = GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext
                .template(gettingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = gettingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("*")),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate)) should be(Set(existingTemplate))
            )
          }
          "rule allows access not to all indices, but there is at least one matching template with at least one index pattern allowed and no alias allowed" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("test2*")),
              aliases = Set.empty
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(indexPattern("test3*"), indexPattern("test4*")),
              aliases = Set.empty
            )
            val existingTemplate3 = LegacyTemplate(
              name = TemplateName("a3"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("test2*")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("t*1*")),
              requestContext = MockRequestContext
                .template(GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2, existingTemplate3),
              templateOperationAfterProcessing =
                GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t1"))),
              allAllowedIndices = Set(clusterIndexName("t*1*")),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate1)) should be(Set(
                  LegacyTemplate(
                    name = TemplateName("t1"),
                    patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
                    aliases = Set.empty
                  )
                ))
            )
          }
          "rule allows access not to all indices, but there is at least one matching template with at least one index pattern allowed and one alias allowed" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("test2*")),
              aliases = Set(clusterIndexName("test1_alias"), clusterIndexName("test2_alias"))
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(indexPattern("test3*"), indexPattern("test4*")),
              aliases = Set.empty
            )
            val existingTemplate3 = LegacyTemplate(
              name = TemplateName("a3"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("test2*")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("t*1*")),
              requestContext = MockRequestContext
                .template(GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2, existingTemplate3),
              templateOperationAfterProcessing =
                GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t1"))),
              allAllowedIndices = Set(clusterIndexName("t*1*")),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate1)) should be(Set(
                  LegacyTemplate(
                    name = TemplateName("t1"),
                    patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
                    aliases = Set(clusterIndexName("test1_alias"))
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
                patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
                aliases = Set.empty
              )
              val existingTemplate2 = LegacyTemplate(
                name = TemplateName("t2"),
                patterns = UniqueNonEmptyList.of(indexPattern("test3")),
                aliases = Set.empty
              )
              val gettingTemplateOperation = GettingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
              assertNotMatchRuleForTemplateRequest(
                configured = NonEmptySet.of(indexNameVar("test3")),
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
              patterns = UniqueNonEmptyList.of(indexPattern("test1")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("*")),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set.empty) should be(Set.empty)
            )
          }
          "rule allows access to index name which is used in template's pattern list" in {
            val addingTemplateOperation = AddingLegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("test1"))
            )
          }
          "rule allows access to index name with wildcard which is a superset of the pattern in template's pattern list" in {
            val addingTemplateOperation = AddingLegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches both patterns in template's pattern list" in {
            val addingTemplateOperation = AddingLegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches pattern in template's pattern list and all aliases (without index placeholder)" in {
            val addingTemplateOperation = AddingLegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set(requestedIndex("test1_alias"), requestedIndex("test2_alias"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches pattern in template's pattern list and all aliases (with index placeholder)" in {
            val addingTemplateOperation = AddingLegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set(requestedIndex("{index}_alias"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("test*"))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to all indices" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingLegacyTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(indexPattern("test2")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("*"))
            )
          }
          "rule allows access to index name which is used in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingLegacyTemplate(existingTemplate.name, existingTemplate.patterns, Set.empty)
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("test1"))
            )
          }
          "rule allows access to index name with wildcard which is a superset of the patten in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingLegacyTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(indexPattern("test2*")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches both patterns in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingLegacyTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2"), indexPattern("test3")),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches pattern in existing template's pattern list and all aliases (without index placeholder)" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingLegacyTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2"), indexPattern("test3")),
              aliases = Set(requestedIndex("test1_alias"), requestedIndex("test2_alias"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches pattern in existing template's pattern list and all aliases (with index placeholder)" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2")),
              aliases = Set.empty
            )
            val addingTemplateOperation = AddingLegacyTemplate(
              name = existingTemplate.name,
              patterns = UniqueNonEmptyList.of(indexPattern("test1"), indexPattern("test2"), indexPattern("test3")),
              aliases = Set(requestedIndex("{index}_alias"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("test*"))
            )
          }
        }
      }
      "not match" when {
        "template with given name doesn't exist" when {
          "rule allows access to index name which is not used in template's pattern list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test2")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access to index name which matches the pattern in template's pattern list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test*")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access to index name with wildcard which is a subset of the pattern in template's pattern list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test*")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access ot index name with wildcard which matches only one pattern in template's pattern list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("index1*")),
                  aliases = Set.empty
                ))
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (without index placeholder)" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
                  aliases = Set(requestedIndex("test1_alias"), requestedIndex("alias_test1"))
                ))
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (with index placeholder)" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
                  aliases = Set(requestedIndex("{index}_alias"), requestedIndex("alias_{index}"))
                ))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to index name which is not used in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test2")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(indexPattern("test1")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access to index name which matches the pattern in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test*")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(indexPattern("test1")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access to index name with wildcard which is a subset of the pattern in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test*")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(indexPattern("test*")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access ot index name with wildcard which matches only one pattern in existing template's pattern list" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("index1*")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = existingTemplate.name,
                  patterns = UniqueNonEmptyList.of(indexPattern("test*")),
                  aliases = Set.empty
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (without index placeholder)" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("index1*")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
                  aliases = Set(requestedIndex("test1_alias"), requestedIndex("alias_test1"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access ot index name with wildcard which matches pattern in template's pattern list but doesn't match all aliases (with index placeholder)" in {
            val existingTemplate = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("test1*"), indexPattern("index1*")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingLegacyTemplate(
                  name = TemplateName("t1"),
                  patterns = UniqueNonEmptyList.of(indexPattern("test1*")),
                  aliases = Set(requestedIndex("{index}_alias"), requestedIndex("alias_{index}"))
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
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext
                .template(DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*")))),
              templateOperationAfterProcessing =
                DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*_ROR_0000000000"))),
              allAllowedIndices = Set(clusterIndexName("*"))
            )
          }
          "rule allows access to specific index" in {
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1")),
              requestContext = MockRequestContext
                .template(DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*")))),
              templateOperationAfterProcessing =
                DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*_ROR_0000000000"))),
              allAllowedIndices = Set(clusterIndexName("index1"))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to all indices" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1")),
              aliases = Set.empty
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1")),
              aliases = Set.empty
            )
            val deletingTemplateOperation = DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("*"))
            )
          }
          "all requested existing templates have only allowed indices" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1"), indexPattern("index2")),
              aliases = Set.empty
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index3")),
              aliases = Set.empty
            )
            val deletingTemplateOperation = DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1"), indexNameVar("index2")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("index1"), clusterIndexName("index2"))
            )
          }
          "all requested existing templates have only allowed indices patterns" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("a1*"), indexPattern("a2*")),
              aliases = Set.empty
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(indexPattern("b*")),
              aliases = Set.empty
            )
            val deletingTemplateOperation = DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("a*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("a*"))
            )
          }
          "all requested existing templates have only allowed indices patterns and aliases" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("a1*"), indexPattern("a2*")),
              aliases = Set(clusterIndexName("alias"))
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("s1"),
              patterns = UniqueNonEmptyList.of(indexPattern("a*")),
              aliases = Set(clusterIndexName("balias"))
            )
            val deletingTemplateOperation = DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("a*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("a*"))
            )
          }
        }
      }
      "not match" when {
        "template with given name exists" when {
          "one of existing requested templates has index which is forbidden" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1")),
              aliases = Set.empty
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1"), indexPattern("index2")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1")),
              requestContext = MockRequestContext
                .template(DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "one of existing requested templates has index pattern which is forbidden" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1*")),
              aliases = Set.empty
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(indexPattern("index1*"), indexPattern("index2*")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1*")),
              requestContext = MockRequestContext
                .template(DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "one of existing requested templates has alias which is forbidden" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("index11*")),
              aliases = Set(clusterIndexName("index11_alias"))
            )
            val existingTemplate2 = LegacyTemplate(
              name = TemplateName("t2"),
              patterns = UniqueNonEmptyList.of(indexPattern("index12*")),
              aliases = Set(clusterIndexName("alias"))
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1*")),
              requestContext = MockRequestContext
                .template(DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "requested existing template has pattern which values form a superset of set of configured index pattern values" in {
            val existingTemplate1 = LegacyTemplate(
              name = TemplateName("t1"),
              patterns = UniqueNonEmptyList.of(indexPattern("i*1")),
              aliases = Set.empty
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index*")),
              requestContext = MockRequestContext
                .template(DeletingLegacyTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1)
            )
          }
        }
      }
    }
  }
}
