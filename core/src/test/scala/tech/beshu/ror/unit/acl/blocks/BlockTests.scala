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

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{Assertion, Inside, TestSuite}
import tech.beshu.ror.accesscontrol.History.{BlockHistory, RuleHistory}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.GeneralIndexRequestBlockContextUpdater
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.NotAuthorized
import tech.beshu.ror.accesscontrol.blocks.Decision.{Denied, Permitted}
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthRule, RegularRule}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{AuthenticationImpersonationCustomSupport, AuthorizationImpersonationCustomSupport}
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, Group, LocalUsers, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.{*, given}
import tech.beshu.ror.utils.uniquelist.UniqueList

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.util.Failure

class BlockTests extends AnyWordSpec with BlockContextAssertion with Inside with BlockTestsMockFactory {

  "A block evaluated for a regular request" should {
    "be denied and contain all history, up to mismatched rule" when {
      "one of rules doesn't match" in {
        def withLoggedUser: GeneralIndexRequestBlockContext => GeneralIndexRequestBlockContext =
          _.withBlockMetadata(_.withLoggedUser(DirectlyLoggedUser(User.Id("user1"))))

        val blockName = Block.Name("test_block")
        val block = new Block(
          name = blockName,
          policy = Block.Policy.Allow,
          verbosity = Block.Verbosity.Info,
          audit = Block.Audit.Enabled,
          rules = NonEmptyList.fromListUnsafe(
            passingRule("r1") ::
              passingRule("r2", withLoggedUser) ::
              notPassingRule("r3") ::
              passingRule("r4") :: Nil
          )
        )
        val requestContext = MockRequestContext.indices
        val result = block.evaluateForRegularRequest(requestContext).runSyncUnsafe(1 second)

        inside(result) {
          case (Decision.Denied(_), BlockHistory.Denied(block, Decision.Denied(_), rulesHistory)) =>
            block.name should be(blockName)
            assertPermitted(rulesHistory(0))(
              hasRuleName = Rule.Name("r1")
            )
            assertPermitted(rulesHistory(1))(
              hasRuleName = Rule.Name("r2")
            )
            assertDenied(rulesHistory(2))(
              hasRuleName = Rule.Name("r3"),
              hasCause = NotAuthorized
            )
            rulesHistory should have size 3
        }
      }
      "one of rules throws exception" in {
        val blockName = Block.Name("test_block")
        val block = new Block(
          name = blockName,
          policy = Block.Policy.Allow,
          verbosity = Block.Verbosity.Info,
          audit = Block.Audit.Enabled,
          rules = NonEmptyList.fromListUnsafe(
            passingRule("r1") :: passingRule("r2") :: throwingRule("r3") :: notPassingRule("r4") :: passingRule("r5") :: Nil
          )
        )
        val requestContext = MockRequestContext.indices
        val result = block.evaluateForRegularRequest(requestContext).runSyncUnsafe(1 second)

        inside(result) {
          case (Decision.Denied(_), BlockHistory.Denied(block, Decision.Denied(_), rulesHistory)) =>
            block.name should be(blockName)
            assertPermitted(rulesHistory(0))(
              hasRuleName = Rule.Name("r1")
            )
            assertPermitted(rulesHistory(1))(
              hasRuleName = Rule.Name("r2")
            )
            assertDenied(rulesHistory(2))(
              hasRuleName = Rule.Name("r3"),
              hasCause = NotAuthorized
            )
            rulesHistory should have size 3
        }
      }
    }
    "be permitted and contain all rules history from the block" in {
      val blockName = Block.Name("test_block")
      val block = new Block(
        name = blockName,
        policy = Block.Policy.Allow,
        verbosity = Block.Verbosity.Info,
        audit = Block.Audit.Enabled,
        rules = NonEmptyList.fromListUnsafe(
          passingRule("r1") :: passingRule("r2") :: passingRule("r3") :: Nil
        )
      )
      val requestContext = MockRequestContext.indices
      val result = block.evaluateForRegularRequest(requestContext).runSyncUnsafe(1 second)

      inside(result) {
        case (Decision.Permitted(blockContext), BlockHistory.Permitted(block, Decision.Permitted(_), rulesHistory)) =>
          block.name should be(blockName)
          assertPermitted(rulesHistory(0))(
            hasRuleName = Rule.Name("r1")
          )
          assertPermitted(rulesHistory(1))(
            hasRuleName = Rule.Name("r2")
          )
          assertPermitted(rulesHistory(2))(
            hasRuleName = Rule.Name("r3")
          )
          rulesHistory should have size 3

          blockContext.blockMetadata should be(BlockMetadata.empty)
          blockContext.filteredIndices should be(Set.empty)
          blockContext.responseHeaders should be(Set.empty)
      }
    }
    "be permitted and contain all rules history from the block with modified block context" in {
      def withLoggedUser: GeneralIndexRequestBlockContext => GeneralIndexRequestBlockContext =
        _.withBlockMetadata(_.withLoggedUser(DirectlyLoggedUser(User.Id("user1"))))

      def withIndices: GeneralIndexRequestBlockContext => GeneralIndexRequestBlockContext =
        _.withIndices(Set(requestedIndex("idx1")), Set(clusterIndexName("idx*")))

      val blockName = Block.Name("test_block")
      val block = new Block(
        name = blockName,
        policy = Block.Policy.Allow,
        verbosity = Block.Verbosity.Info,
        audit = Block.Audit.Enabled,
        rules = NonEmptyList.fromListUnsafe(
          passingRule("r1", withLoggedUser) ::
            passingRule("r2") ::
            passingRule("r3", withIndices) ::
            Nil
        )
      )
      val requestContext = MockRequestContext.indices
      val result = block.evaluateForRegularRequest(requestContext).runSyncUnsafe(1 second)

      inside(result) {
        case (Decision.Permitted(blockContext), BlockHistory.Permitted(block, Decision.Permitted(_), rulesHistory)) =>
          block.name should be(blockName)
          assertPermitted(rulesHistory(0))(
            hasRuleName = Rule.Name("r1")
          )
          assertPermitted(rulesHistory(1))(
            hasRuleName = Rule.Name("r2")
          )
          assertPermitted(rulesHistory(2))(
            hasRuleName = Rule.Name("r3")
          )
          rulesHistory should have size 3

          blockContext.blockMetadata should be(
            BlockMetadata
              .empty
              .withLoggedUser(DirectlyLoggedUser(User.Id("user1")))
          )
          blockContext.filteredIndices should be(Set(requestedIndex("idx1")))
          blockContext.allAllowedIndices should be(Set(clusterIndexName("idx*")))
          blockContext.responseHeaders should be(Set.empty)
      }
    }
    "be permitted and contain all rules history from the block with overwritten logged user" in {
      val blockName = Block.Name("test_block")
      val block = new Block(
        name = blockName,
        policy = Block.Policy.Allow,
        verbosity = Block.Verbosity.Info,
        audit = Block.Audit.Enabled,
        rules = NonEmptyList.fromListUnsafe(
          passingRule("r1", _.withBlockMetadata(_.withLoggedUser(DirectlyLoggedUser(User.Id("user1"))))) ::
            passingRule("r2", _.withBlockMetadata(_.withLoggedUser(DirectlyLoggedUser(User.Id("user2"))))) ::
            Nil
        )
      )
      val requestContext = MockRequestContext.indices
      val result = block.evaluateForRegularRequest(requestContext).runSyncUnsafe(1 second)

      inside(result) {
        case (Decision.Permitted(blockContext), BlockHistory.Permitted(block, Decision.Permitted(_), rulesHistory)) =>
          block.name should be(blockName)
          assertPermitted(rulesHistory(0))(
            hasRuleName = Rule.Name("r1")
          )
          assertPermitted(rulesHistory(1))(
            hasRuleName = Rule.Name("r2")
          )
          rulesHistory should have size 2

          blockContext.blockMetadata should be(
            BlockMetadata
              .empty
              .withLoggedUser(DirectlyLoggedUser(User.Id("user2")))
          )
          blockContext.filteredIndices should be(empty)
          blockContext.allAllowedIndices should be(empty)
          blockContext.responseHeaders should be(empty)
      }
    }
  }

  "A block evaluated for a user metadata request" should {
    "be permitted and return a single result for a block without an authentication/authorization rule" in {
      val block = metadataBlock(
        passingMetadataRule("r1"),
        passingMetadataRule("r2")
      )
      val result = block.evaluateForMetadataRequest(MockRequestContext.metadata).runSyncUnsafe(1 second)

      result.size should be(1)
      inside(result.head) {
        case (Decision.Permitted(_), BlockHistory.Permitted(_, Decision.Permitted(_), rulesHistory)) =>
          rulesHistory.map(_.rule) should be(Vector(Rule.Name("r1"), Rule.Name("r2")))
      }
    }
    "be denied and not run the remaining rules when the authentication/authorization rule denies" in {
      val block = metadataBlock(
        notPassingAuthRule("auth"),
        perGroupKibanaIndexRule("kibana")
      )
      val result = block.evaluateForMetadataRequest(MockRequestContext.metadata).runSyncUnsafe(1 second)

      result.size should be(1)
      inside(result.head) {
        case (Decision.Denied(cause), BlockHistory.Denied(_, Decision.Denied(_), rulesHistory)) =>
          cause should be(Cause.AuthenticationFailed("mock failed"))
          rulesHistory.map(_.rule) should be(Vector(Rule.Name("auth")))
      }
    }
    "be permitted and return one result per available group, each carrying that group as the current one and the full rule history" in {
      val groups = UniqueList.of(group("g1"), group("g2"), group("g3"))
      val block = metadataBlock(
        passingAuthRule("auth", userId("u1"), groups),
        perGroupKibanaIndexRule("kibana")
      )
      val result = block.evaluateForMetadataRequest(MockRequestContext.metadata).runSyncUnsafe(1 second)

      result.size should be(3)
      result.toList.zip(groups.toList).foreach { case (perGroupResult, expectedGroup) =>
        inside(perGroupResult) {
          case (Decision.Permitted(blockContext), BlockHistory.Permitted(_, Decision.Permitted(_), rulesHistory)) =>
            blockContext.blockMetadata.currentGroupId should be(Some(expectedGroup.id))
            rulesHistory.map(_.rule) should be(Vector(Rule.Name("auth"), Rule.Name("kibana")))
        }
      }
    }
    "be permitted and return a single result when the authentication/authorization rule grants no group" in {
      val block = metadataBlock(
        passingAuthRule("auth", userId("u1"), UniqueList.empty),
        perGroupKibanaIndexRule("kibana")
      )
      val result = block.evaluateForMetadataRequest(MockRequestContext.metadata).runSyncUnsafe(1 second)

      result.size should be(1)
      inside(result.head) {
        case (Decision.Permitted(blockContext), BlockHistory.Permitted(_, Decision.Permitted(_), rulesHistory)) =>
          blockContext.blockMetadata.currentGroupId should be(None)
          rulesHistory.map(_.rule) should be(Vector(Rule.Name("auth"), Rule.Name("kibana")))
      }
    }
    "be permitted and resolve a group-dependent rule to a different value for each group" in {
      val groups = UniqueList.of(group("g1"), group("g2"), group("g3"))
      val block = metadataBlock(
        passingAuthRule("auth", userId("u1"), groups),
        perGroupKibanaIndexRule("kibana")
      )
      val result = block.evaluateForMetadataRequest(MockRequestContext.metadata).runSyncUnsafe(1 second)

      val resolvedKibanaIndices = result.toList.collect {
        case (Decision.Permitted(blockContext), _) => blockContext.blockMetadata.kibanaPolicy.flatMap(_.index)
      }
      resolvedKibanaIndices should be(List(
        Some(kibanaIndexName(NonEmptyString.unsafeFrom("kibana_g1"))),
        Some(kibanaIndexName(NonEmptyString.unsafeFrom("kibana_g2"))),
        Some(kibanaIndexName(NonEmptyString.unsafeFrom("kibana_g3")))
      ))
    }
    "be permitted and execute the authentication/authorization rule exactly once regardless of the number of groups" in {
      val authInvocations = new AtomicInteger(0)
      val groups = UniqueList.of(group("g1"), group("g2"), group("g3"))
      val block = metadataBlock(
        passingAuthRule("auth", userId("u1"), groups, authInvocations),
        perGroupKibanaIndexRule("kibana")
      )
      val result = block.evaluateForMetadataRequest(MockRequestContext.metadata).runSyncUnsafe(1 second)

      result.size should be(3)
      authInvocations.get() should be(1)
    }
  }

  private def metadataBlock(rules: Rule*): Block =
    new Block(
      name = Block.Name("test_block"),
      policy = Block.Policy.Allow,
      verbosity = Block.Verbosity.Info,
      audit = Block.Audit.Enabled,
      rules = NonEmptyList.fromListUnsafe(rules.toList)
    )

  private def assertPermitted[T <: BlockContext](ruleHistory: RuleHistory[T])(hasRuleName: Rule.Name): Unit = {
    ruleHistory.rule should be(hasRuleName)
    inside(ruleHistory.decision) { case Permitted(_) => }
  }

  private def assertDenied[T <: BlockContext](ruleHistory: RuleHistory[T])(hasRuleName: Rule.Name, hasCause: Cause): Assertion = {
    ruleHistory.rule should be(hasRuleName)
    ruleHistory.decision should be(Denied(hasCause))
  }
}

trait BlockTestsMockFactory extends MockFactory {
  this: TestSuite =>

  protected def passingRule(ruleName: String,
                            modifyBlockContext: GeneralIndexRequestBlockContext => GeneralIndexRequestBlockContext = identity) =
    new RegularRule {
      override val name: Rule.Name = Rule.Name(ruleName)

      override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] =
        BlockContextUpdater[B] match {
          case GeneralIndexRequestBlockContextUpdater => Task.now(Permitted(modifyBlockContext(blockContext)))
          case _ => throw new IllegalStateException("Assuming that only GeneralIndexRequestBlockContext can be used in this test")
        }
    }

  protected def notPassingRule(ruleName: String) = new RegularRule {
    override val name: Rule.Name = Rule.Name(ruleName)

    override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] =
      Task.now(Denied(NotAuthorized))
  }

  protected def throwingRule(ruleName: String) = new RegularRule {
    override val name: Rule.Name = Rule.Name(ruleName)

    override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] =
      Task.fromTry(Failure(new Exception("sth went wrong")))
  }

  protected def passingMetadataRule(ruleName: String) = new RegularRule {
    override val name: Rule.Name = Rule.Name(ruleName)

    override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] =
      Task.now(Permitted(blockContext))
  }

  // an auth rule that authenticates the user and grants access to the given groups (so group discovery sees them);
  // `authInvocations` counts how many times the rule body actually executes
  protected def passingAuthRule(ruleName: String,
                                userId: User.Id,
                                groups: UniqueList[Group],
                                authInvocations: AtomicInteger = new AtomicInteger(0)) =
    new AuthRule with AuthenticationImpersonationCustomSupport with AuthorizationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name(ruleName)

      override def localUsers: LocalUsers = LocalUsers.NotAvailable
      override implicit def userIdCaseSensitivity: CaseSensitivity = CaseSensitivity.Disabled

      override protected def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
        authInvocations.incrementAndGet()
        Task.now(Permitted(blockContext.withBlockMetadata(_.withLoggedUser(DirectlyLoggedUser(userId)))))
      }

      override protected def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] =
        Task.now(Permitted(blockContext.withBlockMetadata(_.withAvailableGroups(groups))))
    }

  protected def notPassingAuthRule(ruleName: String) =
    new AuthRule with AuthenticationImpersonationCustomSupport with AuthorizationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name(ruleName)

      override def localUsers: LocalUsers = LocalUsers.NotAvailable
      override implicit def userIdCaseSensitivity: CaseSensitivity = CaseSensitivity.Disabled

      override protected def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] =
        Task.now(Denied(Cause.AuthenticationFailed("mock failed")))

      override protected def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] =
        Task.now(Denied(Cause.AuthenticationFailed("mock failed")))
    }

  // a regular rule that resolves a kibana index from the current group, like `index: "kibana_@{acl:current_group}"` would
  protected def perGroupKibanaIndexRule(ruleName: String) = new RegularRule {
    override val name: Rule.Name = Rule.Name(ruleName)

    override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] =
      blockContext.blockMetadata.currentGroupId match {
        case Some(groupId) =>
          val index = kibanaIndexName(NonEmptyString.unsafeFrom(s"kibana_${groupId.value.value}"))
          Task.now(Permitted(blockContext.withBlockMetadata(_.withKibanaIndex(index))))
        case None =>
          Task.now(Permitted(blockContext))
      }
  }

}
