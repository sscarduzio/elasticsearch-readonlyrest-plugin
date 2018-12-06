package tech.beshu.ror.acl.blocks

import cats.data.NonEmptyList
import org.scalatest.Matchers._
import monix.eval.Task
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.requestcontext.RequestContext
import monix.execution.Scheduler.Implicits.global

import scala.language.postfixOps

class BlockTest extends WordSpec {

  "A test" in {
    val passingRule = new Rule {
      override def `match`(context: RequestContext): Task[Boolean] = Task.now(true)
    }
    val notPassingRule = new Rule {
      override def `match`(context: RequestContext): Task[Boolean] = Task.now(false)
    }
    val block = new Block(NonEmptyList.fromList(passingRule :: passingRule :: notPassingRule :: passingRule :: Nil).get)
    val result = block.execute(null).run.runSyncMaybe

    result shouldBe Right("matched" :: "matched" :: "unmatched" :: Nil, Block.ExecutionResult.Unmatched)
  }
}
