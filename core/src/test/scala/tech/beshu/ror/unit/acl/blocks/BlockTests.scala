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

import eu.timepit.refined.auto._
import cats.data.NonEmptyList
import eu.timepit.refined.auto._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.Block.HistoryItem.RuleHistoryItem
import tech.beshu.ror.accesscontrol.blocks.Block.{ExecutionResult, History}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.GeneralIndexRequestBlockContextUpdater
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.unit.acl.blocks.BlockTests.{notPassingRule, passingRule, throwingRule}
import tech.beshu.ror.utils.TestsUtils._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Failure

class BlockTests extends AnyWordSpec with BlockContextAssertion with Inside {

  "A block execution result" should {
    "be mismatched and contain all history, up to mismatched rule" when {
      "one of rules doesn't match" in {
        def withLoggedUser: GeneralIndexRequestBlockContext => GeneralIndexRequestBlockContext =
          _.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(User.Id("user1"))))

        val blockName = Block.Name("test_block")
        val block = new Block(
          name = blockName,
          policy = Block.Policy.Allow,
          verbosity = Block.Verbosity.Info,
          rules = NonEmptyList.fromListUnsafe(
            passingRule("r1") ::
              passingRule("r2", withLoggedUser) ::
              notPassingRule("r3") ::
              passingRule("r4") :: Nil
          )
        )
        val requestContext = MockRequestContext.indices
        val result = block.execute(requestContext).runSyncUnsafe(1 second)

        inside(result) {
          case (ExecutionResult.Mismatched(_), History(`blockName`, historyItems, blockContext)) =>
            historyItems should have size 3
            historyItems(0) should be(RuleHistoryItem(Rule.Name("r1"), Fulfilled(requestContext.initialBlockContext)))
            historyItems(1) should be(RuleHistoryItem(Rule.Name("r2"), Fulfilled(withLoggedUser(requestContext.initialBlockContext))))
            historyItems(2) should be(RuleHistoryItem(Rule.Name("r3"), Rejected()))

            assertBlockContext(loggedUser = Some(DirectlyLoggedUser(User.Id("user1")))) {
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
        val requestContext = MockRequestContext.indices
        val result = block.execute(requestContext).runSyncUnsafe(1 second)

        inside(result) {
          case (ExecutionResult.Mismatched(_), History(`blockName`, historyItems, blockContext)) =>
            historyItems should have size 3
            historyItems(0) should be(RuleHistoryItem(Rule.Name("r1"), Fulfilled(requestContext.initialBlockContext)))
            historyItems(1) should be(RuleHistoryItem(Rule.Name("r2"), Fulfilled(requestContext.initialBlockContext)))
            historyItems(2) should be(RuleHistoryItem(Rule.Name("r3"), Rejected()))

            blockContext.userMetadata should be(UserMetadata.empty)
            blockContext.filteredIndices should be(Set.empty)
            blockContext.responseHeaders should be(Set.empty)
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
      val requestContext = MockRequestContext.indices
      val result = block.execute(requestContext).runSyncUnsafe(1 second)

      inside(result) {
        case (ExecutionResult.Matched(_, _), History(`blockName`, historyItems, blockContext)) =>
          historyItems should have size 3
          historyItems(0) should be(RuleHistoryItem(Rule.Name("r1"), Fulfilled(requestContext.initialBlockContext)))
          historyItems(1) should be(RuleHistoryItem(Rule.Name("r2"), Fulfilled(requestContext.initialBlockContext)))
          historyItems(2) should be(RuleHistoryItem(Rule.Name("r3"), Fulfilled(requestContext.initialBlockContext)))

          blockContext.userMetadata should be(UserMetadata.empty)
          blockContext.filteredIndices should be(Set.empty)
          blockContext.responseHeaders should be(Set.empty)
      }
    }
    "be matched and contain all rules history from the block with modified block context" in {
      def withLoggedUser: GeneralIndexRequestBlockContext => GeneralIndexRequestBlockContext =
        _.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(User.Id("user1"))))

      def withIndices: GeneralIndexRequestBlockContext => GeneralIndexRequestBlockContext =
        _.withIndices(Set(clusterIndexName("idx1")), Set(clusterIndexName("idx*")))

      val blockName = Block.Name("test_block")
      val block = new Block(
        name = blockName,
        policy = Block.Policy.Allow,
        verbosity = Block.Verbosity.Info,
        rules = NonEmptyList.fromListUnsafe(
          passingRule("r1", withLoggedUser) ::
            passingRule("r2") ::
            passingRule("r3", withIndices) ::
            Nil
        )
      )
      val requestContext = MockRequestContext.indices
      val result = block.execute(requestContext).runSyncUnsafe(1 second)

      inside(result) {
        case (ExecutionResult.Matched(_, _), History(`blockName`, historyItems, blockContext)) =>
          historyItems should have size 3
          historyItems(0) should be(RuleHistoryItem(Rule.Name("r1"), Fulfilled(withLoggedUser(requestContext.initialBlockContext))))
          historyItems(1) should be(RuleHistoryItem(Rule.Name("r2"), Fulfilled(withLoggedUser(requestContext.initialBlockContext))))
          historyItems(2) should be(RuleHistoryItem(Rule.Name("r3"), Fulfilled(withLoggedUser(withIndices(requestContext.initialBlockContext)))))

          blockContext.userMetadata should be(
            UserMetadata
              .empty
              .withLoggedUser(DirectlyLoggedUser(User.Id("user1")))
          )
          blockContext.filteredIndices should be(Set(clusterIndexName("idx1")))
          blockContext.allAllowedIndices should be(Set(clusterIndexName("idx*")))
          blockContext.responseHeaders should be(Set.empty)
      }
    }
    "be matched and contain all rules history from the block with overwritten logged user" in {
      def withLoggedUser1: GeneralIndexRequestBlockContext => GeneralIndexRequestBlockContext =
        _.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(User.Id("user1"))))
      def withLoggedUser2: GeneralIndexRequestBlockContext => GeneralIndexRequestBlockContext =
        _.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(User.Id("user2"))))

      val blockName = Block.Name("test_block")
      val block = new Block(
        name = blockName,
        policy = Block.Policy.Allow,
        verbosity = Block.Verbosity.Info,
        rules = NonEmptyList.fromListUnsafe(
          passingRule("r1", _.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(User.Id("user1"))))) ::
          passingRule("r2", _.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(User.Id("user2"))))) ::
            Nil
        )
      )
      val requestContext = MockRequestContext.indices
      val result = block.execute(requestContext).runSyncUnsafe(1 second)

      inside(result) {
        case (ExecutionResult.Matched(_, _), History(`blockName`, historyItems, blockContext)) =>
          historyItems should have size 2
          historyItems(0) should be(RuleHistoryItem(Rule.Name("r1"), Fulfilled(withLoggedUser1(requestContext.initialBlockContext))))
          historyItems(1) should be(RuleHistoryItem(Rule.Name("r2"), Fulfilled(withLoggedUser2(requestContext.initialBlockContext))))

          blockContext.userMetadata should be(
            UserMetadata
              .empty
              .withLoggedUser(DirectlyLoggedUser(User.Id("user2")))
          )
          blockContext.filteredIndices should be(empty)
          blockContext.allAllowedIndices should be(empty)
          blockContext.responseHeaders should be(empty)
      }
    }
  }
}

object BlockTests extends MockFactory {

  private def passingRule(ruleName: String,
                          modifyBlockContext: GeneralIndexRequestBlockContext => GeneralIndexRequestBlockContext = identity) =
    new RegularRule {
      override val name: Rule.Name = Rule.Name(ruleName)

      override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
        BlockContextUpdater[B] match {
          case GeneralIndexRequestBlockContextUpdater => Task.now(Fulfilled(modifyBlockContext(blockContext)))
          case _ => throw new IllegalStateException("Assuming that only GeneralIndexRequestBlockContext can be used in this test")
        }
    }

  private def notPassingRule(ruleName: String) = new RegularRule {
    override val name: Rule.Name = Rule.Name(ruleName)

    override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
      Task.now(Rejected())
  }

  private def throwingRule(ruleName: String) = new RegularRule {
    override val name: Rule.Name = Rule.Name(ruleName)

    override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
      Task.fromTry(Failure(new Exception("sth went wrong")))
  }

}