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
package tech.beshu.ror.unit.acl.blocks

import cats.data.{NonEmptyList, NonEmptySet}
import org.scalatest.Matchers._
import monix.eval.Task
import org.scalatest.{Inside, WordSpec}
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import tech.beshu.ror.acl.blocks.{Block, BlockContext, RequestContextInitiatedBlockContext}
import tech.beshu.ror.acl.blocks.Block.{ExecutionResult, History, HistoryItem}
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.acl.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.acl.domain.{Group, IndexName, LoggedUser, User}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.unit.acl.blocks.BlockTests.{notPassingRule, passingRule, throwingRule}
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.acl.orders._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Failure

class BlockTests extends WordSpec with BlockContextAssertion with Inside {

  "A block execution result" should {
    "be unmatched and contain all history, up to unmatched rule" when {
      "one of rules doesn't match" in {
        val blockName = Block.Name("test_block")
        val block = new Block(
          name = blockName,
          policy = Block.Policy.Allow,
          verbosity = Block.Verbosity.Info,
          rules = NonEmptyList.fromListUnsafe(
            passingRule("r1") ::
              passingRule("r2", _.withLoggedUser(DirectlyLoggedUser(User.Id("user1".nonempty)))) ::
              notPassingRule("r3") ::
              passingRule("r4", _.withCurrentGroup(Group("group1".nonempty))) :: Nil
          )
        )
        val requestContext = MockRequestContext.default
        val result = block.execute(requestContext).runSyncUnsafe(1 second)

        inside(result) {
          case (ExecutionResult.Unmatched(_), History(`blockName`, historyItems, blockContext)) =>
            historyItems should be (Vector(
              HistoryItem(Rule.Name("r1"), matched = true),
              HistoryItem(Rule.Name("r2"), matched = true),
              HistoryItem(Rule.Name("r3"), matched = false)
            ))
            assertBlockContext(loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty)))) {
              blockContext
            }
        }
      }
      "one of rules throws exception" in {
        val blockName = Block.Name("test_block")
        val block = new Block(
          name = blockName,
          policy = Block.Policy.Allow,
          verbosity = Block.Verbosity.Info,
          rules = NonEmptyList.fromListUnsafe(
            passingRule("r1") :: passingRule("r2") :: throwingRule("r3") :: notPassingRule("r4") :: passingRule("r5") :: Nil
          )
        )
        val requestContext = MockRequestContext.default
        val result = block.execute(requestContext).runSyncUnsafe(1 second)

        inside(result) {
          case (ExecutionResult.Unmatched(_), History(`blockName`, historyItems, blockContext)) =>
            historyItems should be(Vector(
              HistoryItem(Rule.Name("r1"), matched = true),
              HistoryItem(Rule.Name("r2"), matched = true),
              HistoryItem(Rule.Name("r3"), matched = false)
            ))
            assertBlockContext(
              expected = RequestContextInitiatedBlockContext.fromRequestContext(requestContext),
              current = blockContext
            )
        }
      }
    }
    "be matched and contain all rules history from the block" in {
      val blockName = Block.Name("test_block")
      val block = new Block(
        name = blockName,
        policy = Block.Policy.Allow,
        verbosity = Block.Verbosity.Info,
        rules = NonEmptyList.fromListUnsafe(
          passingRule("r1") :: passingRule("r2") :: passingRule("r3") :: Nil
        )
      )
      val requestContext = MockRequestContext.default
      val result = block.execute(requestContext).runSyncUnsafe(1 second)

      inside(result) {
        case (ExecutionResult.Matched(_, _), History(`blockName`, historyItems, blockContext)) =>
          historyItems should be(Vector(
            HistoryItem(Rule.Name("r1"), matched = true),
            HistoryItem(Rule.Name("r2"), matched = true),
            HistoryItem(Rule.Name("r3"), matched = true)
          ))
          assertBlockContext(
            expected = RequestContextInitiatedBlockContext.fromRequestContext(requestContext),
            current = blockContext
          )
      }
    }
    "be matched and contain all rules history from the block with modified block context" in {
      val blockName = Block.Name("test_block")
      val block = new Block(
        name = blockName,
        policy = Block.Policy.Allow,
        verbosity = Block.Verbosity.Info,
        rules = NonEmptyList.fromListUnsafe(
          passingRule("r1", _.withLoggedUser(DirectlyLoggedUser(User.Id("user1".nonempty)))) ::
            passingRule("r2", _.withCurrentGroup(Group("group1".nonempty))) ::
            passingRule("r3", _.withIndices(NonEmptySet.one(IndexName("idx1".nonempty)))) ::
            Nil
        )
      )
      val requestContext = MockRequestContext.default
      val result = block.execute(requestContext).runSyncUnsafe(1 second)

      inside(result) {
        case (ExecutionResult.Matched(_, _), History(`blockName`, historyItems, blockContext)) =>
          historyItems should be(Vector(
            HistoryItem(Rule.Name("r1"), matched = true),
            HistoryItem(Rule.Name("r2"), matched = true),
            HistoryItem(Rule.Name("r3"), matched = true)
          ))
          assertBlockContext(
            loggedUser = Some(DirectlyLoggedUser(User.Id("user1".nonempty))),
            currentGroup = Some(Group("group1".nonempty)),
            indices = Set(IndexName("idx1".nonempty))
          ) {
            blockContext
          }
      }
    }
  }
}

object BlockTests extends MockFactory {

  private def passingRule(ruleName: String, modifyBlockContext: BlockContext => BlockContext = identity) =
    new RegularRule {
      override val name: Rule.Name = Rule.Name(ruleName)
      override def check(requestContext: RequestContext, blockContext: BlockContext): Task[RuleResult] =
        Task.now(Fulfilled(modifyBlockContext(blockContext)))
    }
  private def notPassingRule(ruleName: String) = new RegularRule {
    override val name: Rule.Name = Rule.Name(ruleName)
    override def check(requestContext: RequestContext, blockContext: BlockContext): Task[RuleResult] =
      Task.now(Rejected)
  }
  private def throwingRule(ruleName: String) = new RegularRule {
    override val name: Rule.Name = Rule.Name(ruleName)
    override def check(requestContext: RequestContext, blockContext: BlockContext): Task[RuleResult] =
      Task.fromTry(Failure(new Exception("sth went wrong")))
  }
}