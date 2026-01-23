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
package tech.beshu.ror.unit.acl

import cats.data.{NonEmptyList, NonEmptySet}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.AccessControlList.ForbiddenCause
import tech.beshu.ror.accesscontrol.AccessControlList.ForbiddenCause.OperationNotAllowed
import tech.beshu.ror.accesscontrol.AccessControlList.UserMetadataRequestResult.{Allow, ForbiddenBy, ForbiddenByMismatched}
import tech.beshu.ror.accesscontrol.EnabledAccessControlList
import tech.beshu.ror.accesscontrol.EnabledAccessControlList.AccessControlListStaticContext
import tech.beshu.ror.accesscontrol.blocks.Block.Policy
import tech.beshu.ror.accesscontrol.blocks.BlockContext.UserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.{BlockMetadata, UserMetadata}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.orders.forbiddenCauseOrder
import tech.beshu.ror.accesscontrol.request.{RestRequest, UserMetadataRequestContext}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueList

class EnabledAccessControlListTests extends AnyWordSpec with MockFactory with Inside {

  import MockedBlockResult.*

  "An AccessControlList (old)" when {
    "metadata request is called (with current group ID)" should {
      "allow request" which {
        "response will contain collected metadata from matched blocks" in {
          val acl = createAcl(
            block("b1", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("logserver"), group("ext-onlio"), group("admins"), group("ext-odp"), group("ext-enex"), group("dohled-nd-pce"), group("helpdesk")))),
            block("b2", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("logserver"), group("ext-onlio"), group("admins"), group("ext-odp"), group("ext-enex"), group("dohled-nd-pce"), group("helpdesk")))),
            block("b3", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("logserver"), group("ext-onlio"), group("admins"), group("ext-odp"), group("ext-enex"), group("dohled-nd-pce"), group("helpdesk")))),
            block("b4", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("logserver"), group("ext-onlio"), group("admins"), group("ext-odp"), group("ext-enex"), group("dohled-nd-pce"), group("helpdesk")))),
            block("b5", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("logserver"), group("ext-onlio"), group("admins"), group("ext-odp"), group("ext-enex"), group("dohled-nd-pce"), group("helpdesk")))),
            block("b6", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("logserver"), group("ext-onlio"), group("admins"), group("ext-odp"), group("ext-enex"), group("dohled-nd-pce"), group("helpdesk")))),
            block("b7", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("logserver"), group("ext-onlio"), group("admins"), group("ext-odp"), group("ext-enex"), group("dohled-nd-pce"), group("helpdesk")))),
          )

          val userMetadataRequestResult = acl
            .handleMetadataRequest(mockUserMetadataRequestContext(currentGroup = Some(group("admins"))))
            .runSyncUnsafe()
            .result

          inside(userMetadataRequestResult) {
            case Allow(userMetadata@UserMetadata.WithGroups(_)) =>
              userMetadata.groupMetadata.keys.toList should contain theSameElementsAs {
                GroupId("logserver") :: GroupId("ext-onlio") :: GroupId("admins") :: GroupId("ext-odp") ::
                  GroupId("ext-enex") :: GroupId("dohled-nd-pce") :: GroupId("helpdesk") :: Nil
              }
          }
        }
        "FORBID policy block is matched and its position in ACL is after some ALLOW-policy matched blocks" in {
          val acl = createAcl(
            block("b1", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("g1"), group("admins")))),
            block("b2", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("g2"), group("admins")))),
            block("b3", Policy.Forbid(), result = Matched(userId("sulc1"), UniqueList.of(group("g3"), group("admins")))),
            block("b4", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("g4"), group("admins")))),
            block("b5", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("g5"), group("admins")))),
            block("b6", Policy.Forbid(), result = Matched(userId("sulc1"), UniqueList.of(group("g6"), group("admins")))),
            block("b7", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("g7"), group("admins")))),
          )

          val userMetadataRequestResult = acl
            .handleMetadataRequest(mockUserMetadataRequestContext(currentGroup = Some(group("admins"))))
            .runSyncUnsafe()
            .result

          inside(userMetadataRequestResult) {
            case Allow(userMetadata@UserMetadata.WithGroups(_)) =>
              val result = userMetadata
                .groupMetadata.values
                .map(metadata => (metadata.group, metadata.block.name.value, metadata.block.policy))
                .toList
              result should be (List(
                (group("g1"), "b1", Policy.Allow),
                (group("admins"), "b1", Policy.Allow),
                (group("g2"), "b2", Policy.Allow),
                (group("g3"), "b3", Policy.Forbid()),
                (group("g4"), "b4", Policy.Allow),
                (group("g5"), "b5", Policy.Allow),
                (group("g6"), "b6", Policy.Forbid()),
                (group("g7"), "b7", Policy.Allow),
              ))
          }
        }
      }
      "forbid request" when {
        "FORBID policy block is matched and its position in ACL is before any other ALLOW-policy matched block" in {
          val acl = createAcl(
            block("b1", Policy.Forbid(), result = Matched(userId("sulc1"), UniqueList.of(group("g1"), group("admins")))),
            block("b2", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("g2"), group("admins")))),
            block("b3", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("g3"), group("admins")))),
            block("b4", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("g4"), group("admins")))),
            block("b5", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("g5"), group("admins")))),
            block("b6", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("g6"), group("admins")))),
            block("b7", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("g7"), group("admins")))),
          )

          val userMetadataRequestResult = acl
            .handleMetadataRequest(mockUserMetadataRequestContext(currentGroup = Some(group("admins"))))
            .runSyncUnsafe()
            .result

          inside(userMetadataRequestResult) {
            case ForbiddenBy(blockContext, block) =>
              block.name should be(Block.Name("b1"))
              block.policy should be(Block.Policy.Forbid(None))
          }
        }
        "none block is matched because of non-existing current group" in {
          val acl = createAcl(
            block("b1", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("g2"), group("admins")))),
            block("b2", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("g3"), group("admins")))),
            block("b3", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("g4"), group("admins")))),
            block("b4", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("g5"), group("admins")))),
            block("b5", Policy.Forbid(), result = Matched(userId("sulc2"), UniqueList.of(group("g1"), group("admins")))),
            block("b6", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("g6"), group("admins")))),
            block("b7", Policy.Allow, result = Matched(userId("sulc1"), UniqueList.of(group("g7"), group("admins")))),
          )

          val userMetadataRequestResult = acl
            .handleMetadataRequest(mockUserMetadataRequestContext(currentGroup = Some(group("users"))))
            .runSyncUnsafe()
            .result

          inside(userMetadataRequestResult) {
            case ForbiddenByMismatched(causes) =>
              causes should be(NonEmptySet.of[ForbiddenCause](OperationNotAllowed))
          }
        }
      }
    }
  }

  private def createAcl(blocks: Block*) = {
    val blocksNel = NonEmptyList.fromListUnsafe(blocks.toList)
    new EnabledAccessControlList(
      blocksNel,
      new AccessControlListStaticContext(
        blocks = blocksNel,
        globalSettings = GlobalSettings(
          showBasicAuthPrompt = true,
          forbiddenRequestMessage = "Forbidden",
          flsEngine = FlsEngine.default,
          settingsIndex = RorSettingsIndex(IndexName.Full(".readonlyrest")),
          userIdCaseSensitivity = CaseSensitivity.Enabled,
          usersDefinitionDuplicateUsernamesValidationEnabled = true
        ),
        obfuscatedHeaders = Set.empty
      )
    )
  }

  private sealed trait MockedBlockResult
  private object MockedBlockResult {
    final case class Matched(userId: User.Id, groups: UniqueList[Group]) extends MockedBlockResult
    case object Mismatched extends MockedBlockResult
  }

  private def block(name: String, policy: Block.Policy, result: MockedBlockResult) = {
    new Block(
      name = Block.Name(name),
      policy = policy,
      verbosity = Block.Verbosity.Info,
      audit = Block.Audit.Enabled,
      rules = NonEmptyList.of(result match {
        case MockedBlockResult.Matched(userId, groups) => matchedRule(userId, groups)
        case MockedBlockResult.Mismatched => mismatchedRule
      })
    )
  }

  private def matchedRule(userId: User.Id, groups: UniqueList[Group]): Rule = new RegularRule {
    override val name: Rule.Name = Rule.Name("auth")

    override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
      Task.now(RuleResult.Fulfilled(
        blockContext.withBlockMetadata(_
          .withLoggedUser(DirectlyLoggedUser(userId))
          .withAvailableGroups(groups)
        )
      ))
    }
  }

  private def mismatchedRule: Rule = new RegularRule {
    override val name: Rule.Name = Rule.Name("auth")

    override protected def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
      Task.now(RuleResult.Rejected())
  }

  private def mockUserMetadataRequestContext(currentGroup: Option[Group]) = {
    val rc = mock[MockUserMetadataRequestContext]
    (() => rc.initialBlockContext)
      .expects()
      .returning(UserMetadataRequestBlockContext(rc, BlockMetadata.empty, Set.empty, List.empty))
      .anyNumberOfTimes()
    val rr = mock[RestRequest]
    (() => rc.restRequest)
      .expects()
      .returning(rr)
      .anyNumberOfTimes()
    (() => rc.currentGroupId)
      .expects()
      .returning(currentGroup.map(_.id))
      .anyNumberOfTimes()
    (() => rc.action)
      .expects()
      .returning(Action.RorAction.RorUserMetadataAction)
      .anyNumberOfTimes()
    rc
  }

  private trait MockUserMetadataRequestContext extends UserMetadataRequestContext {
    override type BLOCK_CONTEXT = UserMetadataRequestBlockContext
  }
}
