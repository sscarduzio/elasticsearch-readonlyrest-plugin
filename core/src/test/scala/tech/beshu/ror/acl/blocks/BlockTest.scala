package tech.beshu.ror.acl.blocks

import cats.data.NonEmptyList
import org.scalatest.Matchers._
import monix.eval.Task
import org.scalatest.WordSpec
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import tech.beshu.ror.acl.blocks.Block.{History, HistoryItem}
import tech.beshu.ror.acl.blocks.BlockTest.{dummyRequestContext, throwingRule, notPassingRule, passingRule}
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.request.RequestContext

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Failure

class BlockTest extends WordSpec {

  "A block execution result" should {
    "be unmatched and contain all history, up to unmatched rule" when {
      "one of rules doesn't match" in {
        val blockName = Block.Name("test_block")
        val block = new Block(
          name = blockName,
          policy = Block.Policy.Allow,
          rules = NonEmptyList.fromList(passingRule :: passingRule :: notPassingRule :: passingRule :: Nil).get
        )
        val result = block.execute(dummyRequestContext).runSyncUnsafe(1 second)

        result shouldBe (
          Block.ExecutionResult.Unmatched,
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
          rules = NonEmptyList.fromList(passingRule :: passingRule :: throwingRule :: notPassingRule :: passingRule :: Nil).get
        )
        val result = block.execute(dummyRequestContext).runSyncUnsafe(1 second)

        result shouldBe (
          Block.ExecutionResult.Unmatched,
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
        rules = NonEmptyList.fromList(passingRule :: passingRule :: passingRule :: Nil).get
      )
      val result = block.execute(dummyRequestContext).runSyncUnsafe(1 second)

      result shouldBe (
        Block.ExecutionResult.Matched,
        History(
          blockName,
          Vector(
            HistoryItem(passingRule.name, matched = true),
            HistoryItem(passingRule.name, matched = true),
            HistoryItem(passingRule.name, matched = true)
          )
        )
      )
    }
  }
}

object BlockTest extends MockFactory {

  private val passingRule = new RegularRule {
    override val name: Rule.Name = Rule.Name("matching")
    override def `match`(context: RequestContext): Task[Boolean] = Task.now(true)
  }
  private val notPassingRule = new RegularRule {
    override val name: Rule.Name = Rule.Name("non-matching")
    override def `match`(context: RequestContext): Task[Boolean] = Task.now(false)
  }
  private val throwingRule = new RegularRule {
    override val name: Rule.Name = Rule.Name("non-matching-throwing")
    override def `match`(context: RequestContext): Task[Boolean] = Task.fromTry(Failure(new Exception("sth went wrong")))
  }
  private val dummyRequestContext = mock[RequestContext]
}