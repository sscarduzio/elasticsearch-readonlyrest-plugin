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

import cats.implicits._
import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.rules.IndicesRule
import tech.beshu.ror.acl.domain.{Action, IndexName, IndexWithAliases}
import tech.beshu.ror.acl.orders.indexOrder
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.variables.runtime.{RuntimeSingleResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}

import scala.collection.SortedSet

class IndicesRuleTests extends WordSpec with MockFactory {

  "An IndicesRule" should {
    "match" when {
      "no index passed, one is configured, there is one real index" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test")),
          requestIndices = Set.empty,
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test"), Set.empty))
          ),
          found = Set(IndexName("test"))
        )
      }
      "'_all' passed, one is configured, there is one real index" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test")),
          requestIndices = Set(IndexName("_all")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test"), Set.empty))
          ),
          found = Set(IndexName("test"))
        )
      }
      "'*' passed, one is configured, there is one real index" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test")),
          requestIndices = Set(IndexName("*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test"), Set.empty))
          ),
          found = Set(IndexName("test"))
        )
      }
      "one full name index passed, one full name index configured, no real indices" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test")),
          requestIndices = Set(IndexName("test"))
        )
      }
      "one wildcard index passed, one full name index configured, no real indices" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test")),
          requestIndices = Set(IndexName("te*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test"), Set.empty)),
          ),
          found = Set(IndexName("test"))
        )
      }
      "one full name index passed, one wildcard index configured, no real indices" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("t*")),
          requestIndices = Set(IndexName("test"))
        )
      }
      "two full name indexes passed, the same two full name indexes configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test1"), indexNameValueFrom("test2")),
          requestIndices = Set(IndexName("test2"), IndexName("test1"))
        )
      }
      "two full name indexes passed, one the same, one different index configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test1"), indexNameValueFrom("test2")),
          requestIndices = Set(IndexName("test1"), IndexName("test3")),
          found = Set(IndexName("test1"))
        )
      }
      "two matching wildcard indexes passed, two full name indexes configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test1"), indexNameValueFrom("test2")),
          requestIndices = Set(IndexName("*2"), IndexName("*1")),
          found = Set(IndexName("test1"), IndexName("test2"))
        )
      }
      "two full name indexes passed, two matching wildcard indexes configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("*1"), indexNameValueFrom("*2")),
          requestIndices = Set(IndexName("test2"), IndexName("test1"))
        )
      }
      "two full name indexes passed, one matching full name and one non-matching wildcard index configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test1"), indexNameValueFrom("*2")),
          requestIndices = Set(IndexName("test1"), IndexName("test3")),
          found = Set(IndexName("test1"))
        )
      }
      "one matching wildcard index passed and one non-matching full name index, two full name indexes configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test1"), indexNameValueFrom("*2")),
          requestIndices = Set(IndexName("*1"), IndexName("test3")),
          found = Set(IndexName("test1"))
        )
      }
      "one full name alias passed, full name index related to that alias configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test-index")),
          requestIndices = Set(IndexName("test-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test-index"), Set(IndexName("test-alias"))))
          ),
          found = Set(IndexName("test-index"))
        )
      }
      "wildcard alias passed, full name index related to alias matching passed one configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test-index")),
          requestIndices = Set(IndexName("*-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test-index"), Set(IndexName("test-alias"))))
          ),
          found = Set(IndexName("test-index"))
        )
      }
      "one full name alias passed, wildcard index configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("*-index")),
          requestIndices = Set(IndexName("test-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(IndexWithAliases(IndexName("test-index"), Set(IndexName("test-alias"))))
          ),
          found = Set(IndexName("test-index"))
        )
      }
      "one alias passed, only subset of alias indices configured" in {
        assertMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test-index1"), indexNameValueFrom("test-index2")),
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
          requestIndices = Set(IndexName("_all"))
        )
      }
      "'*' passed, one is configured, no real indices" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test")),
          requestIndices = Set(IndexName("*"))
        )
      }
      "one full name index passed, different one full name index configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test1")),
          requestIndices = Set(IndexName("test2"))
        )
      }
      "one wildcard index passed, non-matching index with full name configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test1")),
          requestIndices = Set(IndexName("*2"))
        )
      }
      "one full name index passed, non-matching index with wildcard configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("*1")),
          requestIndices = Set(IndexName("test2"))
        )
      }
      "two full name indexes passed, different two full name indexes configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test1"), indexNameValueFrom("test2")),
          requestIndices = Set(IndexName("test4"), IndexName("test3"))
        )
      }
      "two wildcard indexes passed, non-matching two full name indexes configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test1"), indexNameValueFrom("test2")),
          requestIndices = Set(IndexName("*4"), IndexName("*3"))
        )
      }
      "two full name indexes passed, non-matching two wildcard indexes configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("*1"), indexNameValueFrom("*2")),
          requestIndices = Set(IndexName("test4"), IndexName("test3"))
        )
      }
      "one full name alias passed, full name index with no alias configured" in {
        assertNotMatchRule(
          configured = NonEmptySet.of(indexNameValueFrom("test-index")),
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
          configured = NonEmptySet.of(indexNameValueFrom("test-index")),
          requestIndices = Set(IndexName("*-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              IndexWithAliases(IndexName("test-index"), Set.empty),
              IndexWithAliases(IndexName("test-index2"), Set(IndexName("test-alias")))
            )
          )
        )
      }
    }
  }

  private def assertMatchRule(configured: NonEmptySet[RuntimeSingleResolvableVariable[IndexName]],
                              requestIndices: Set[IndexName],
                              modifyRequestContext: MockRequestContext => MockRequestContext = identity,
                              found: Set[IndexName] = Set.empty) =
    assertRule(configured, requestIndices, isMatched = true, modifyRequestContext, found)

  private def assertNotMatchRule(configured: NonEmptySet[RuntimeSingleResolvableVariable[IndexName]],
                                 requestIndices: Set[IndexName],
                                 modifyRequestContext: MockRequestContext => MockRequestContext = identity) =
    assertRule(configured, requestIndices, isMatched = false, modifyRequestContext, Set.empty)

  private def assertRule(configuredValues: NonEmptySet[RuntimeSingleResolvableVariable[IndexName]],
                         requestIndices: Set[IndexName],
                         isMatched: Boolean,
                         modifyRequestContext: MockRequestContext => MockRequestContext,
                         found: Set[IndexName]) = {
    val rule = new IndicesRule(IndicesRule.Settings(configuredValues))
    val requestContext = modifyRequestContext apply MockRequestContext.default
      .copy(
        indices = requestIndices,
        action = Action("indices:data/read/search"),
        isReadOnlyRequest = true,
        involvesIndices = true,
        allIndicesAndAliases = Set(
          IndexWithAliases(IndexName("test1"), Set.empty),
          IndexWithAliases(IndexName("test2"), Set.empty),
          IndexWithAliases(IndexName("test3"), Set.empty),
          IndexWithAliases(IndexName("test4"), Set.empty),
          IndexWithAliases(IndexName("test5"), Set.empty)
        )
      )
    val blockContext = mock[BlockContext]
    val returnedBlock = if(found.nonEmpty) {
      val newBlock = mock[BlockContext]
      (blockContext.withIndices _).expects(NonEmptySet.fromSetUnsafe(SortedSet.empty[IndexName] ++ found)).returning(newBlock)
      newBlock
    } else {
      blockContext
    }
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(returnedBlock)
      else Rejected
    }
  }

  private def indexNameValueFrom(value: String): RuntimeSingleResolvableVariable[IndexName] = {
    implicit val provider: EnvVarsProvider = OsEnvVarsProvider
    RuntimeResolvableVariableCreator
      .createSingleResolvableVariableFrom(value, str => Right(IndexName(str)))
      .right
      .getOrElse(throw new IllegalStateException(s"Cannot create IndexName Value from $value"))
  }

}
