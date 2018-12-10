package tech.beshu.ror.acl.blocks

import cats.data.NonEmptyList
import org.scalatest.Matchers._
import monix.eval.Task
import org.scalatest.WordSpec
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import tech.beshu.ror.acl.blocks.BlockTest.{notPassingRule, passingRule, dummyRequestContext}
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.requestcontext.RequestContext

import scala.language.postfixOps

class BlockTest extends WordSpec {

  "A block execution result" should {
    "be unmatched and contain all history, up to unmatched rule" in {
      val block = new Block(NonEmptyList.fromList(passingRule :: passingRule :: notPassingRule :: passingRule :: Nil).get)
      val result = block.execute(dummyRequestContext).run.runSyncMaybe

      result shouldBe Right("matched" :: "matched" :: "unmatched" :: Nil, Block.ExecutionResult.Unmatched)
    }

    "be matched and contain all rules history from the block" in {
      val block = new Block(NonEmptyList.fromList(passingRule :: passingRule :: passingRule :: Nil).get)
      val result = block.execute(dummyRequestContext).run.runSyncMaybe

      result shouldBe Right("matched" :: "matched" :: "matched" :: Nil, Block.ExecutionResult.Matched)
    }
  }
}

object BlockTest extends MockFactory {

  private val passingRule = new RegularRule {
    override def `match`(context: RequestContext): Task[Boolean] = Task.now(true)
  }
  private val notPassingRule = new RegularRule {
    override def `match`(context: RequestContext): Task[Boolean] = Task.now(false)
  }
  private val dummyRequestContext = mock[RequestContext]
}