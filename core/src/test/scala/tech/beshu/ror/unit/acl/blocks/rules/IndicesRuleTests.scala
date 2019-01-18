package tech.beshu.ror.unit.acl.blocks.rules

import cats.implicits._
import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.rules.IndicesRule
import tech.beshu.ror.acl.aDomain.{Action, IndexName}
import tech.beshu.ror.acl.orders.indexOrder
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.{BlockContext, Value}

import scala.collection.SortedSet

class IndicesRuleTests extends WordSpec with MockFactory {

  "An IndicesRule" should {
    "pass with single simple index" in {
      assertMatchRule(
        configured = NonEmptySet.of(indexNameValueFrom("public-asd")),
        indices = Set(IndexName("public-asd"))
      )
    }
    // fixme: this seems to not work properly (Java test also fails here)
    "pass with single wildcard" in {
      assertMatchRule(
        configured = NonEmptySet.of(indexNameValueFrom("public-*")),
        indices = Set(IndexName("public-asd"))
      )
    }
    // fixme: same this
    "pass with reverse wildcard" in {
      assertMatchRule(
        configured = NonEmptySet.of(indexNameValueFrom("public-asd")),
        indices = Set(IndexName("publi-*")),
        _.copy(
          allIndicesAndAliases = Set(IndexName("public-asd"))
        )
      )
    }
    "return allowed subset" in {
      assertMatchRule(
        configured = NonEmptySet.of(indexNameValueFrom("a")),
        indices = Set(IndexName("a"), IndexName("b"), IndexName("c")),
        _.copy(
          allIndicesAndAliases = Set(IndexName("a"), IndexName("b"), IndexName("c")),
        ),
        found = Set(IndexName("a"))
      )
    }
    "152 ?!" in {
      assertNotMatchRule(
        configured = NonEmptySet.of(indexNameValueFrom("perfmon*")),
        indices = Set(IndexName("another_index")),
        _.copy(
          allIndicesAndAliases = Set(IndexName("perfmon-bfarm"), IndexName("another_index"))
        )
      )
    }
  }

  private def assertMatchRule(configured: NonEmptySet[Value[IndexName]],
                              indices: Set[IndexName],
                              modifyRequestContext: MockRequestContext => MockRequestContext = identity,
                              found: Set[IndexName] = Set.empty) =
    assertRule(configured, indices, isMatched = true, modifyRequestContext, found)

  private def assertNotMatchRule(configured: NonEmptySet[Value[IndexName]],
                                 indices: Set[IndexName],
                                 modifyRequestContext: MockRequestContext => MockRequestContext = identity,
                                 found: Set[IndexName] = Set.empty) =
    assertRule(configured, indices, isMatched = false, modifyRequestContext, found)

  private def assertRule(configuredValues: NonEmptySet[Value[IndexName]],
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
        involvesIndices = true
      )
    val blockContext = mock[BlockContext]
    val returnedBlock = if(found.nonEmpty) {
      val newBlock = mock[BlockContext]
      (blockContext.setIndices _).expects(NonEmptySet.fromSetUnsafe(SortedSet.empty[IndexName] ++ found)).returning(newBlock)
      newBlock
    } else {
      blockContext
    }
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(returnedBlock)
      else Rejected
    }
  }

  private def indexNameValueFrom(value: String): Value[IndexName] = {
    Value
      .fromString(value, rv => Right(IndexName(rv.value)))
      .right
      .getOrElse(throw new IllegalStateException(s"Cannot create IndexName Value from $value"))
  }
}
