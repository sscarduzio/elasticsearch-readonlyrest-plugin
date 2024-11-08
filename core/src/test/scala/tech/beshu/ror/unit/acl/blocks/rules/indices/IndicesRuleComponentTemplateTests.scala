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
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.BlockContext.RequestedIndex
import tech.beshu.ror.accesscontrol.domain.Template.ComponentTemplate
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.{AddingComponentTemplate, DeletingComponentTemplates, GettingComponentTemplates}
import tech.beshu.ror.accesscontrol.domain.{TemplateName, TemplateNamePattern}
import tech.beshu.ror.accesscontrol.orders.indexOrder
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.{clusterIndexName, requestedIndex, unsafeNes}

private [indices] trait IndicesRuleComponentTemplateTests {
  this: BaseIndicesRuleTests =>

  "An Indices Rule for component template context" when {
    "getting component template request is sent" should {
      "match" when {
        "template doesn't exist" in {
          val gettingTemplateOperation = GettingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
          assertMatchRuleForTemplateRequest(
            configured = NonEmptySet.of(indexNameVar("test*")),
            requestContext = MockRequestContext.template(gettingTemplateOperation),
            templateOperationAfterProcessing = gettingTemplateOperation,
            allAllowedIndices = Set(clusterIndexName("test*"))
          )
        }
        "template exists" when {
          "rule allows access to all indices" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(clusterIndexName("alias1"))
            )
            val gettingTemplateOperation = GettingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
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
          "rule allows access not to all indices, but there is at least one alias allowed" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(clusterIndexName("test1_alias"), clusterIndexName("test2_alias"))
            )
            val existingTemplate2 = ComponentTemplate(
              name = TemplateName("t2"),
              aliases = Set.empty
            )
            val existingTemplate3 = ComponentTemplate(
              name = TemplateName("d3"),
              aliases = Set.empty
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("t*1*")),
              requestContext = MockRequestContext
                .template(GettingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2, existingTemplate3),
              templateOperationAfterProcessing =
                GettingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*"))),
              allAllowedIndices = Set(clusterIndexName("t*1*")),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate1, existingTemplate2)) should be(Set(
                  ComponentTemplate(
                    name = TemplateName("t1"),
                    aliases = Set(clusterIndexName("test1_alias"))
                  ),
                  existingTemplate2
                ))
            )
          }
          "all aliases are forbidden" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(clusterIndexName("alias1"))
            )
            val gettingTemplateOperation = GettingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1")),
              requestContext = MockRequestContext
                .template(gettingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = gettingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("index1")),
              additionalAssertions = blockContext =>
                blockContext.responseTemplateTransformation(Set(existingTemplate)) should be(Set(
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
              aliases = Set(requestedIndex("alias1"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("*"))
            )
          }
          "rule allows access to index name which is used in template's aliases list" in {
            val addingTemplateOperation = AddingComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(requestedIndex("alias1"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("alias1")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("alias1"))
            )
          }
          "rule allows access to index name with wildcard which is a superset of the pattern in template's aliases list" in {
            val addingTemplateOperation = AddingComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(requestedIndex("test1*"))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext.template(addingTemplateOperation),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("test*"))
            )
          }
          "rule allows access to index name with wildcard which matches both patterns in template's aliases list" in {
            val addingTemplateOperation = AddingComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(requestedIndex("test1"), requestedIndex("test2"))
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
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(clusterIndexName("test1"))
            )
            val addingTemplateOperation = AddingComponentTemplate(
              name = existingTemplate.name,
              aliases = Set(requestedIndex("test2"))
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
          "rule allows access to index name which is used in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(clusterIndexName("test1"))
            )
            val addingTemplateOperation = AddingComponentTemplate(
              name = existingTemplate.name,
              aliases = existingTemplate.aliases.map(RequestedIndex(_, excluded = false))
            )
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(addingTemplateOperation)
                .addExistingTemplates(existingTemplate),
              templateOperationAfterProcessing = addingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("test1"))
            )
          }
          "rule allows access to index name with wildcard which is a superset of the patten in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(clusterIndexName("test1*"))
            )
            val addingTemplateOperation = AddingComponentTemplate(
              name = existingTemplate.name,
              aliases = Set(requestedIndex("test2*"))
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
          "rule allows access to index name with wildcard which matches both patterns in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(clusterIndexName("test1"), clusterIndexName("test2"))
            )
            val addingTemplateOperation = AddingComponentTemplate(
              name = existingTemplate.name,
              aliases = Set(requestedIndex("test1"), requestedIndex("test2"), requestedIndex("test3"))
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
        "template with given name doesn't exit" when {
          "rule allows access to index name which is not used in template's aliases list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = TemplateName("t1"),
                  aliases = Set(requestedIndex("test2"))
                ))
            )
          }
          "rule allows access to index name which matches the pattern in template's aliases list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = TemplateName("t1"),
                  aliases = Set(requestedIndex("test*"))
                ))
            )
          }
          "rule allows access to index name with wildcard which is a subset of the pattern in template's aliases list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1*")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = TemplateName("t1"),
                  aliases = Set(requestedIndex("test*"))
                ))
            )
          }
          "rule allows access ot index name with wildcard which matches only one pattern in template's aliases list" in {
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = TemplateName("t1"),
                  aliases = Set(requestedIndex("test1*"), requestedIndex("index1*"))
                ))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to index name which is not used in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(clusterIndexName("test2"))
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = existingTemplate.name,
                  aliases = Set(requestedIndex("test1"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access to index name which matches the pattern in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(clusterIndexName("test*"))
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = existingTemplate.name,
                  aliases = Set(requestedIndex("test1"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access to index name with wildcard which is a subset of the pattern in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(clusterIndexName("test*"))
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test1*")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = existingTemplate.name,
                  aliases = Set(requestedIndex("test*"))
                ))
                .addExistingTemplates(existingTemplate)
            )
          }
          "rule allows access ot index name with wildcard which matches only one pattern in existing template's aliases list" in {
            val existingTemplate = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(clusterIndexName("test1*"), clusterIndexName("index1*"))
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("test*")),
              requestContext = MockRequestContext
                .template(AddingComponentTemplate(
                  name = existingTemplate.name,
                  aliases = Set(requestedIndex("test*"))
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
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext
                .template(DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*")))),
              templateOperationAfterProcessing =
                DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*_ROR_0000000000"))),
              allAllowedIndices = Set(clusterIndexName("*"))
            )
          }
          "rule allows access to specific index" in {
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1")),
              requestContext = MockRequestContext
                .template(DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*")))),
              templateOperationAfterProcessing =
                DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*_ROR_0000000000"))),
              allAllowedIndices = Set(clusterIndexName("index1"))
            )
          }
        }
        "template with given name exists" when {
          "rule allows access to all indices" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(clusterIndexName("index1"))
            )
            val existingTemplate2 = ComponentTemplate(
              name = TemplateName("s1"),
              aliases = Set(clusterIndexName("index1"))
            )
            val deletingTemplateOperation = DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("*")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("*"))
            )
          }
          "all requested existing templates have only allowed aliases" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(clusterIndexName("index1"), clusterIndexName("index2"))
            )
            val existingTemplate2 = ComponentTemplate(
              name = TemplateName("s1"),
              aliases = Set(clusterIndexName("index3"))
            )
            val deletingTemplateOperation = DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t1")))
            assertMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1"), indexNameVar("index2")),
              requestContext = MockRequestContext
                .template(deletingTemplateOperation)
                .addExistingTemplates(existingTemplate1, existingTemplate2),
              templateOperationAfterProcessing = deletingTemplateOperation,
              allAllowedIndices = Set(clusterIndexName("index1"), clusterIndexName("index2"))
            )
          }
          "all requested existing templates have only allowed aliases patterns" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(clusterIndexName("a1*"), clusterIndexName("a2*"))
            )
            val existingTemplate2 = ComponentTemplate(
              name = TemplateName("s1"),
              aliases = Set(clusterIndexName("b*"))
            )
            val deletingTemplateOperation = DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*")))
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
          "one of existing requested templates has alias which is forbidden" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(clusterIndexName("index1"))
            )
            val existingTemplate2 = ComponentTemplate(
              name = TemplateName("t2"),
              aliases = Set(clusterIndexName("index1"), clusterIndexName("index2"))
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1")),
              requestContext = MockRequestContext
                .template(DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "one of existing requested templates has alias pattern which is forbidden" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(clusterIndexName("index1*"))
            )
            val existingTemplate2 = ComponentTemplate(
              name = TemplateName("t2"),
              aliases = Set(clusterIndexName("index1*"), clusterIndexName("index2*"))
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index1*")),
              requestContext = MockRequestContext
                .template(DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1, existingTemplate2)
            )
          }
          "requested existing template has pattern which values form a superset of set of configured alias pattern values" in {
            val existingTemplate1 = ComponentTemplate(
              name = TemplateName("t1"),
              aliases = Set(clusterIndexName("i*1"))
            )
            assertNotMatchRuleForTemplateRequest(
              configured = NonEmptySet.of(indexNameVar("index*")),
              requestContext = MockRequestContext
                .template(DeletingComponentTemplates(NonEmptyList.of(TemplateNamePattern("t*"))))
                .addExistingTemplates(existingTemplate1)
            )
          }
        }
      }
      "multi filterable request tries to fetch data for not allowed index" in {
        assertNotMatchRuleForMultiIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1")),
          indexPacks = Indices.Found(Set(requestedIndex("test2"))) :: Nil
        )
      }
    }
  }
}
