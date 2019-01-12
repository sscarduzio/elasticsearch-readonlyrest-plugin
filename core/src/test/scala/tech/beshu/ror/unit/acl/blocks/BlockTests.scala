package tech.beshu.ror.unit.acl.blocks

import cats.data.NonEmptyList
import org.scalatest.Matchers._
import monix.eval.Task
import org.scalatest.WordSpec
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import tech.beshu.ror.unit.acl.blocks.Block.{ExecutionResult, History, HistoryItem}
import tech.beshu.ror.unit.acl.blocks.BlockTests.{dummyRequestContext, notPassingRule, passingRule, throwingRule}
import tech.beshu.ror.unit.acl.blocks.rules.Rule
import tech.beshu.ror.unit.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.unit.acl.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.unit.acl.request.RequestContext

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Failure

class BlockTests extends WordSpec {

  "A block execution result" should {
    "be unmatched and contain all history, up to unmatched rule" when {
      "one of rules doesn't match" in {
        val blockName = Block.Name("test_block")
        val block = new Block(
          name = blockName,
          policy = Block.Policy.Allow,
          verbosity = Block.Verbosity.Info,
          rules = NonEmptyList.fromList(passingRule :: passingRule :: notPassingRule :: passingRule :: Nil).get
        )
        val result = block.execute(dummyRequestContext).runSyncUnsafe(1 second)

        result shouldBe (
          ExecutionResult.Unmatched,
          History(
            blockName,
            Vector(
              HistoryItem(passingRule.name, matched = true),
              HistoryItem(passingRule.name, matched = true),
              HistoryItem(notPassingRule.name, matched = false)
            )
          )
        )
      }
      "one of rules throws exception" in {
        val blockName = Block.Name("test_block")
        val block = new Block(
          name = blockName,
          policy = Block.Policy.Allow,
          verbosity = Block.Verbosity.Info,
          rules = NonEmptyList.fromList(passingRule :: passingRule :: throwingRule :: notPassingRule :: passingRule :: Nil).get
        )
        val result = block.execute(dummyRequestContext).runSyncUnsafe(1 second)

        result shouldBe (
          ExecutionResult.Unmatched,
          History(
            blockName,
            Vector(
              HistoryItem(passingRule.name, matched = true),
              HistoryItem(passingRule.name, matched = true),
              HistoryItem(throwingRule.name, matched = false)
            )
          )
        )
      }
    }
    "be matched and contain all rules history from the block" in {
      val blockName = Block.Name("test_block")
      val block = new Block(
        name = blockName,
        policy = Block.Policy.Allow,
        verbosity = Block.Verbosity.Info,
        rules = NonEmptyList.fromList(passingRule :: passingRule :: passingRule :: Nil).get
      )
      val result = block.execute(dummyRequestContext).runSyncUnsafe(1 second)

      result._1 should matchPattern { case ExecutionResult.Matched(_, _) => }
      result._2 shouldBe History(
        blockName,
        Vector(
          HistoryItem(passingRule.name, matched = true),
          HistoryItem(passingRule.name, matched = true),
          HistoryItem(passingRule.name, matched = true)
        )
      )
    }
  }
}

object BlockTests extends MockFactory {

  private val passingRule = new RegularRule {
    override val name: Rule.Name = Rule.Name("matching")
    override def check(requestContext: RequestContext, blockContext: BlockContext): Task[RuleResult] =
      Task.now(Fulfilled(blockContext))
  }
  private val notPassingRule = new RegularRule {
    override val name: Rule.Name = Rule.Name("non-matching")
    override def check(requestContext: RequestContext, blockContext: BlockContext): Task[RuleResult] =
      Task.now(Rejected)
  }
  private val throwingRule = new RegularRule {
    override val name: Rule.Name = Rule.Name("non-matching-throwing")
    override def check(requestContext: RequestContext, blockContext: BlockContext): Task[RuleResult] =
      Task.fromTry(Failure(new Exception("sth went wrong")))
  }
  private val dummyRequestContext = mock[RequestContext]
}