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

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.IndicesRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.domain.{Action, IndexName, IndexWithAliases}
import tech.beshu.ror.accesscontrol.orders.indexOrder
import tech.beshu.ror.mocks.{MockGeneralIndexRequestContext, MockRequestContext}
import tech.beshu.ror.utils.TestsUtils._

class IndicesRuleTests extends WordSpec with MockFactory {

  "An IndicesRule" should {
    "match" when {
      "no index passed, one is configured, there is one real index" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test")),
          requestIndices = Set.empty,
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test".nonempty), Set.empty))
          ),
          found = Set(IndexName("test".nonempty)),
        )
      }
      "'_all' passed, one is configured, there is one real index" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test")),
          requestIndices = Set(IndexName("_all".nonempty)),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test".nonempty), Set.empty))
          ),
          found = Set(IndexName("test".nonempty))
        )
      }
      "'*' passed, one is configured, there is one real index" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test")),
          requestIndices = Set(IndexName("*".nonempty)),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test".nonempty), Set.empty))
          ),
          found = Set(IndexName("test".nonempty))
        )
      }
      "one full name index passed, one full name index configured, no real indices" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test")),
          requestIndices = Set(IndexName("test".nonempty)),
          found = Set(IndexName("test".nonempty))
        )
      }
      "one wildcard index passed, one full name index configured, no real indices" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test")),
          requestIndices = Set(IndexName("te*".nonempty)),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test".nonempty), Set.empty)),
          ),
          found = Set(IndexName("test".nonempty))
        )
      }
      "one full name index passed, one wildcard index configured, no real indices" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("t*")),
          requestIndices = Set(IndexName("test".nonempty)),
          found = Set(IndexName("test".nonempty))
        )
      }
      "two full name indexes passed, the same two full name indexes configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test1"), indexNameValueFrom("test2")),
          requestIndices = Set(IndexName("test2".nonempty), IndexName("test1".nonempty)),
          found = Set(IndexName("test2".nonempty), IndexName("test1".nonempty))
        )
      }
      "two full name indexes passed, one the same, one different index configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test1"), indexNameValueFrom("test2")),
          requestIndices = Set(IndexName("test1".nonempty), IndexName("test3".nonempty)),
          found = Set(IndexName("test1".nonempty))
        )
      }
      "two matching wildcard indexes passed, two full name indexes configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test1"), indexNameValueFrom("test2")),
          requestIndices = Set(IndexName("*2".nonempty), IndexName("*1".nonempty)),
          found = Set(IndexName("test1".nonempty), IndexName("test2".nonempty))
        )
      }
      "two full name indexes passed, two matching wildcard indexes configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("*1"), indexNameValueFrom("*2")),
          requestIndices = Set(IndexName("test2".nonempty), IndexName("test1".nonempty)),
          found = Set(IndexName("test2".nonempty), IndexName("test1".nonempty))
        )
      }
      "two full name indexes passed, one matching full name and one non-matching wildcard index configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test1"), indexNameValueFrom("*2")),
          requestIndices = Set(IndexName("test1".nonempty), IndexName("test3".nonempty)),
          found = Set(IndexName("test1".nonempty))
        )
      }
      "one matching wildcard index passed and one non-matching full name index, two full name indexes configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test1"), indexNameValueFrom("*2")),
          requestIndices = Set(IndexName("*1".nonempty), IndexName("test3".nonempty)),
          found = Set(IndexName("test1".nonempty))
        )
      }
      "one full name alias passed, full name index related to that alias configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test-index")),
          requestIndices = Set(IndexName("test-alias".nonempty)),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test-index".nonempty), Set(IndexName("test-alias".nonempty))))
          ),
          found = Set(IndexName("test-index".nonempty))
        )
      }
      "wildcard alias passed, full name index related to alias matching passed one configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test-index")),
          requestIndices = Set(IndexName("*-alias".nonempty)),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test-index".nonempty), Set(IndexName("test-alias".nonempty))))
          ),
          found = Set(IndexName("test-index".nonempty))
        )
      }
      "one full name alias passed, wildcard index configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("*-index")),
          requestIndices = Set(IndexName("test-alias".nonempty)),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test-index".nonempty), Set(IndexName("test-alias".nonempty))))
          ),
          found = Set(IndexName("test-index".nonempty))
        )
      }
      "one alias passed, only subset of alias indices configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test-index1"), indexNameValueFrom("test-index2")),
          requestIndices = Set(IndexName("test-alias".nonempty)),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(IndexName("test-index1".nonempty), Set(IndexName("test-alias".nonempty))),
              IndexWithAliases(IndexName("test-index2".nonempty), Set(IndexName("test-alias".nonempty))),
              IndexWithAliases(IndexName("test-index3".nonempty), Set(IndexName("test-alias".nonempty))),
              IndexWithAliases(IndexName("test-index4".nonempty), Set(IndexName("test-alias".nonempty)))
            )
          ),
          found = Set(IndexName("test-index1".nonempty), IndexName("test-index2".nonempty))
        )
      }
      "cross cluster index is used together with local index" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("odd:test1*"), indexNameValueFrom("local*")),
          requestIndices = Set(IndexName("local_index*".nonempty), IndexName("odd:test1_index*".nonempty)),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(IndexName("local_index1".nonempty), Set.empty),
              IndexWithAliases(IndexName("local_index2".nonempty), Set.empty),
              IndexWithAliases(IndexName("other".nonempty), Set.empty)
            )
          ),
          found = Set(
            IndexName("local_index1".nonempty),
            IndexName("local_index2".nonempty),
            IndexName("odd:test1_index*".nonempty)
          )
        )
      }
    }
    "not match" when {
      "no index passed, one is configured, no real indices" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test")),
          requestIndices = Set.empty
        )
      }
      "'_all' passed, one is configured, no real indices" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test")),
          requestIndices = Set(IndexName("_all".nonempty))
        )
      }
      "'*' passed, one is configured, no real indices" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test")),
          requestIndices = Set(IndexName("*".nonempty))
        )
      }
      "one full name index passed, different one full name index configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test1")),
          requestIndices = Set(IndexName("test2".nonempty))
        )
      }
      "one wildcard index passed, non-matching index with full name configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test1")),
          requestIndices = Set(IndexName("*2".nonempty))
        )
      }
      "one full name index passed, non-matching index with wildcard configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("*1")),
          requestIndices = Set(IndexName("test2".nonempty))
        )
      }
      "two full name indexes passed, different two full name indexes configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test1"), indexNameValueFrom("test2")),
          requestIndices = Set(IndexName("test4".nonempty), IndexName("test3".nonempty))
        )
      }
      "two wildcard indexes passed, non-matching two full name indexes configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test1"), indexNameValueFrom("test2")),
          requestIndices = Set(IndexName("*4".nonempty), IndexName("*3".nonempty))
        )
      }
      "two full name indexes passed, non-matching two wildcard indexes configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("*1"), indexNameValueFrom("*2")),
          requestIndices = Set(IndexName("test4".nonempty), IndexName("test3".nonempty))
        )
      }
      "one full name alias passed, full name index with no alias configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test-index")),
          requestIndices = Set(IndexName("test-alias".nonempty)),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(IndexName("test-index".nonempty), Set.empty),
              IndexWithAliases(IndexName("test-index2".nonempty), Set(IndexName("test-alias".nonempty)))
            )
          )
        )
      }
      "wildcard alias passed, full name index with no alias configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test-index")),
          requestIndices = Set(IndexName("*-alias".nonempty)),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(IndexName("test-index".nonempty), Set.empty),
              IndexWithAliases(IndexName("test-index2".nonempty), Set(IndexName("test-alias".nonempty)))
            )
          )
        )
      }
      "full name index passed, index alias configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test12-alias")),
          requestIndices = Set(IndexName("test-index1".nonempty)),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(IndexName("test-index1".nonempty), Set(IndexName("test12-alias".nonempty))),
              IndexWithAliases(IndexName("test-index2".nonempty), Set(IndexName("test12-alias".nonempty))),
              IndexWithAliases(IndexName("test-index3".nonempty), Set(IndexName("test34-alias".nonempty))),
              IndexWithAliases(IndexName("test-index4".nonempty), Set(IndexName("test34-alias".nonempty)))
            )
          )
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
    val rule = new IndicesRule(IndicesRule.Settings(configuredValues, mustInvolveIndices = false))
    val requestContext = modifyRequestContext apply MockRequestContext.indices
      .copy(
        filteredIndices = requestIndices,
        action = Action("indices:data/read/search"),
        isReadOnlyRequest = true,
        hasRemoteClusters = true,
        allIndicesAndAliases = Set(
          IndexWithAliases(IndexName("test1".nonempty), Set.empty),
          IndexWithAliases(IndexName("test2".nonempty), Set.empty),
          IndexWithAliases(IndexName("test3".nonempty), Set.empty),
          IndexWithAliases(IndexName("test4".nonempty), Set.empty),
          IndexWithAliases(IndexName("test5".nonempty), Set.empty)
        )
      )
    val blockContext = GeneralIndexRequestBlockContext(
      requestContext,
      UserMetadata.from(requestContext),
      Set.empty,
      requestIndices,
      Set.empty
    )
    rule.check(blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(GeneralIndexRequestBlockContext(
        requestContext,
        UserMetadata.from(requestContext),
        Set.empty,
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

  private def indexNameValueFrom(value: String): RuntimeMultiResolvableVariable[IndexName] = {
    RuntimeResolvableVariableCreator
      .createMultiResolvableVariableFrom(value.nonempty)(AlwaysRightConvertible.from(IndexName.apply))
      .right
      .getOrElse(throw new IllegalStateException(s"Cannot create IndexName Value from $value"))
  }

}
