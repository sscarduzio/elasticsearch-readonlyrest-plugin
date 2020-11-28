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
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.accesscontrol.blocks.Block.{ExecutionResult, History, HistoryItem}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.GeneralIndexRequestBlockContextUpdater
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorConfigurationIndex, User}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.unit.acl.blocks.BlockTests.{notPassingRule, passingRule, throwingRule}
import tech.beshu.ror.utils.TestsUtils._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Failure

class BlockTests extends WordSpec with BlockContextAssertion with Inside {

  "A block execution result" should {
    "be mismatched and contain all history, up to mismatched rule" when {
      "one of rules doesn't match" in {
        val blockName = Block.Name("test_block")
        val block = new Block(
          name = blockName,
          policy = Block.Policy.Allow,
          verbosity = Block.Verbosity.Info,
          rules = NonEmptyList.fromListUnsafe(
            passingRule("r1") ::
              passingRule("r2", _.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(User.Id("user1".nonempty))))) ::
              notPassingRule("r3") ::
              passingRule("r4") :: Nil
          ),
          BlockTests.defaultGlobalSettings
        )
        val requestContext = MockRequestContext.indices
        val result = block.execute(requestContext).runSyncUnsafe(1 second)

        inside(result) {
          case (ExecutionResult.Mismatched(_), History(`blockName`, historyItems, blockContext)) =>
            historyItems should have size 3
            historyItems(0).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].rule should be(Rule.Name("r1"))
            historyItems(0).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].result shouldBe a[RuleResult.Fulfilled[_]]
            historyItems(1).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].rule should be(Rule.Name("r2"))
            historyItems(1).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].result shouldBe a[RuleResult.Fulfilled[_]]
            historyItems(2).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].rule should be(Rule.Name("r3"))
            historyItems(2).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].result shouldBe a[RuleResult.Rejected[_]]

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
          ),
          BlockTests.defaultGlobalSettings
        )
        val requestContext = MockRequestContext.indices
        val result = block.execute(requestContext).runSyncUnsafe(1 second)

        inside(result) {
          case (ExecutionResult.Mismatched(_), History(`blockName`, historyItems, blockContext)) =>
            historyItems should have size 3
            historyItems(0).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].rule should be(Rule.Name("r1"))
            historyItems(0).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].result shouldBe a[RuleResult.Fulfilled[_]]
            historyItems(1).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].rule should be(Rule.Name("r2"))
            historyItems(1).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].result shouldBe a[RuleResult.Fulfilled[_]]
            historyItems(2).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].rule should be(Rule.Name("r3"))
            historyItems(2).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].result shouldBe a[RuleResult.Rejected[_]]

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
        ),
        BlockTests.defaultGlobalSettings
      )
      val requestContext = MockRequestContext.indices
      val result = block.execute(requestContext).runSyncUnsafe(1 second)

      inside(result) {
        case (ExecutionResult.Matched(_, _), History(`blockName`, historyItems, blockContext)) =>
          historyItems should have size 3
          historyItems(0).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].rule should be(Rule.Name("r1"))
          historyItems(0).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].result shouldBe a[RuleResult.Fulfilled[_]]
          historyItems(1).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].rule should be(Rule.Name("r2"))
          historyItems(1).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].result shouldBe a[RuleResult.Fulfilled[_]]
          historyItems(2).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].rule should be(Rule.Name("r3"))
          historyItems(2).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].result shouldBe a[RuleResult.Fulfilled[_]]

          blockContext.userMetadata should be(UserMetadata.empty)
          blockContext.filteredIndices should be(Set.empty)
          blockContext.responseHeaders should be(Set.empty)
      }
    }
    "be matched and contain all rules history from the block with modified block context" in {
      val blockName = Block.Name("test_block")
      val block = new Block(
        name = blockName,
        policy = Block.Policy.Allow,
        verbosity = Block.Verbosity.Info,
        rules = NonEmptyList.fromListUnsafe(
          passingRule("r1", _.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(User.Id("user1".nonempty))))) ::
            passingRule("r2") ::
            passingRule("r3", _.withIndices(Set(IndexName("idx1".nonempty)), Set(IndexName("idx*".nonempty)))) ::
            Nil
        ),
        BlockTests.defaultGlobalSettings
      )
      val requestContext = MockRequestContext.indices
      val result = block.execute(requestContext).runSyncUnsafe(1 second)

      inside(result) {
        case (ExecutionResult.Matched(_, _), History(`blockName`, historyItems, blockContext)) =>
          historyItems should have size 3
          historyItems(0).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].rule should be(Rule.Name("r1"))
          historyItems(0).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].result shouldBe a[RuleResult.Fulfilled[_]]
          historyItems(1).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].rule should be(Rule.Name("r2"))
          historyItems(1).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].result shouldBe a[RuleResult.Fulfilled[_]]
          historyItems(2).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].rule should be(Rule.Name("r3"))
          historyItems(2).asInstanceOf[HistoryItem.RuleHistoryItem[GeneralIndexRequestBlockContext]].result shouldBe a[RuleResult.Fulfilled[_]]

          blockContext.userMetadata should be(
            UserMetadata
              .empty
              .withLoggedUser(DirectlyLoggedUser(User.Id("user1".nonempty)))
          )
          blockContext.filteredIndices should be(Set(IndexName("idx1".nonempty)))
          blockContext.allAllowedIndices should be(Set(IndexName("idx*".nonempty)))
          blockContext.responseHeaders should be(Set.empty)
      }
    }
  }
}

object BlockTests extends MockFactory {

  private def passingRule(ruleName: String,
                          modifyBlockContext: GeneralIndexRequestBlockContext => GeneralIndexRequestBlockContext = identity) =
    new RegularRule {
      override val name: Rule.Name = Rule.Name(ruleName)
      override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
        BlockContextUpdater[B] match {
          case GeneralIndexRequestBlockContextUpdater => Task.now(Fulfilled(modifyBlockContext(blockContext)))
          case _ => throw new IllegalStateException("Assuming that only GeneralIndexRequestBlockContext can be used in this test")
        }
    }

  private def notPassingRule(ruleName: String) = new RegularRule {
    override val name: Rule.Name = Rule.Name(ruleName)
    override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
      Task.now(Rejected())
  }

  private def throwingRule(ruleName: String) = new RegularRule {
    override val name: Rule.Name = Rule.Name(ruleName)
    override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
      Task.fromTry(Failure(new Exception("sth went wrong")))
  }

  private val defaultGlobalSettings = GlobalSettings(
    showBasicAuthPrompt = true,
    forbiddenRequestMessage = "forbidden",
    flsEngine = FlsEngine.ESWithLucene,
    configurationIndex = RorConfigurationIndex(IndexName(".readonlyrest")),
    indexAuditTemplate = None
  )
}