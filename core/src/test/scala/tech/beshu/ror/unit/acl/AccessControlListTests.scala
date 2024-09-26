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

import cats.data.NonEmptyList
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.AccessControl.ForbiddenCause.OperationNotAllowed
import tech.beshu.ror.accesscontrol.AccessControl.UserMetadataRequestResult.{Allow, Forbidden}
import tech.beshu.ror.accesscontrol.acl.AccessControlList
import tech.beshu.ror.accesscontrol.acl.AccessControlList.AccessControlListStaticContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RegularRule
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.UniqueList

class AccessControlListTests extends AnyWordSpec with MockFactory with Inside {

  "An AccessControlList" when {
    "metadata request is called" should {
      "allow request" which {
        "response will contain collected metadata from matched blocks" in {
          val acl = createAcl(NonEmptyList.of(
            mockAllowedPolicyBlock("b1", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("logserver"), group("ext-onlio"), group("admins"), group("ext-odp"), group("ext-enex"), group("dohled-nd-pce"), group("helpdesk")))),
            mockAllowedPolicyBlock("b2", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("logserver"), group("ext-onlio"), group("admins"), group("ext-odp"), group("ext-enex"), group("dohled-nd-pce"), group("helpdesk")))),
            mockAllowedPolicyBlock("b3", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("logserver"), group("ext-onlio"), group("admins"), group("ext-odp"), group("ext-enex"), group("dohled-nd-pce"), group("helpdesk")))),
            mockAllowedPolicyBlock("b4", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("logserver"), group("ext-onlio"), group("admins"), group("ext-odp"), group("ext-enex"), group("dohled-nd-pce"), group("helpdesk")))),
            mockAllowedPolicyBlock("b5", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("logserver"), group("ext-onlio"), group("admins"), group("ext-odp"), group("ext-enex"), group("dohled-nd-pce"), group("helpdesk")))),
            mockAllowedPolicyBlock("b6", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("logserver"), group("ext-onlio"), group("admins"), group("ext-odp"), group("ext-enex"), group("dohled-nd-pce"), group("helpdesk")))),
            mockAllowedPolicyBlock("b7", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("logserver"), group("ext-onlio"), group("admins"), group("ext-odp"), group("ext-enex"), group("dohled-nd-pce"), group("helpdesk")))),
          ))

          val userMetadataRequestResult = acl
            .handleMetadataRequest(mockMetadataRequestContext("admins"))
            .runSyncUnsafe()
            .result

          inside(userMetadataRequestResult) {
            case Allow(userMetadata, _) =>
              userMetadata.availableGroups.toList should contain theSameElementsAs {
                group("logserver") :: group("ext-onlio") :: group("admins") :: group("ext-odp") ::
                  group("ext-enex") :: group("dohled-nd-pce") :: group("helpdesk") :: Nil
              }
              userMetadata.currentGroupId shouldBe Some(GroupId("admins"))
          }
        }
        "FORBID policy block is matched and its position in ACL is after some ALLOW-policy matched blocks" in {
          val acl = createAcl(NonEmptyList.of(
            mockAllowedPolicyBlock("b1", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("g1"), group("admins")))),
            mockAllowedPolicyBlock("b2", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("g2"), group("admins")))),
            mockForbidPolicyBlock("b3", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("g3"), group("admins")))),
            mockAllowedPolicyBlock("b4", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("g4"), group("admins")))),
            mockAllowedPolicyBlock("b5", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("g5"), group("admins")))),
            mockAllowedPolicyBlock("b6", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("g6"), group("admins")))),
            mockAllowedPolicyBlock("b7", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("g7"), group("admins")))),
          ))

          val userMetadataRequestResult = acl
            .handleMetadataRequest(mockMetadataRequestContext("admins"))
            .runSyncUnsafe()
            .result

          inside(userMetadataRequestResult) {
            case Allow(userMetadata, _) =>
              userMetadata.availableGroups.toList should contain theSameElementsAs {
                group("g1") :: group("g2") :: group("admins") :: Nil
              }
              userMetadata.currentGroupId shouldBe Some(GroupId("admins"))
          }
        }
      }
      "forbid request" when {
        "FORBID policy block is matched and its position in ACL is before any other ALLOW-policy matched block" in {
          val acl = createAcl(NonEmptyList.of(
            mockForbidPolicyBlock("b1", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("g1"), group("admins")))),
            mockAllowedPolicyBlock("b2", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("g2"), group("admins")))),
            mockAllowedPolicyBlock("b3", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("g3"), group("admins")))),
            mockAllowedPolicyBlock("b4", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("g4"), group("admins")))),
            mockAllowedPolicyBlock("b5", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("g5"), group("admins")))),
            mockAllowedPolicyBlock("b6", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("g6"), group("admins")))),
            mockAllowedPolicyBlock("b7", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroupId(GroupId("admins")).withAvailableGroups(UniqueList.of(group("g7"), group("admins")))),
          ))

          val userMetadataRequestResult = acl
            .handleMetadataRequest(mockMetadataRequestContext("admins"))
            .runSyncUnsafe()
            .result

          inside(userMetadataRequestResult) {
            case Forbidden(causes) =>
              causes.toNonEmptyList should be (NonEmptyList.one(OperationNotAllowed))
          }
        }
      }
    }
  }

  private def createAcl(blocks: NonEmptyList[Block]) = {
    new AccessControlList(
      blocks,
      new AccessControlListStaticContext(
        blocks,
        GlobalSettings(
          showBasicAuthPrompt = true,
          forbiddenRequestMessage = "Forbidden",
          flsEngine = FlsEngine.default,
          configurationIndex = RorConfigurationIndex(IndexName.Full(".readonlyrest")),
          userIdCaseSensitivity = CaseSensitivity.Enabled
        ),
        Set.empty
      )
    )
  }

  private def mockAllowedPolicyBlock(name: String, userMetadata: UserMetadata) = {
    mockBlock(name, Block.Policy.Allow, userMetadata)
  }

  private def mockForbidPolicyBlock(name: String, userMetadata: UserMetadata) = {
    mockBlock(name, Block.Policy.Forbid(None), userMetadata)
  }

  private def mockBlock(name: String, policy: Block.Policy, userMetadata: UserMetadata) = {
    new Block(
      Block.Name(name),
      policy,
      Block.Verbosity.Info,
      NonEmptyList.of(
        new RegularRule {
          override val name: Rule.Name = Rule.Name("auth")
          override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] = {
            Task.now(Rule.RuleResult.Fulfilled(blockContext.withUserMetadata(_ => userMetadata)))
          }
        }
      )
    )
  }

  private def user(userName: NonEmptyString) =
    LoggedUser.DirectlyLoggedUser(User.Id(userName))

  private def mockMetadataRequestContext(preferredGroupId: NonEmptyString) = {
    val userMetadata = UserMetadata.empty.withCurrentGroupId(GroupId(preferredGroupId))
    val rc = mock[MetadataRequestContext]
    (() => rc.initialBlockContext)
      .expects()
      .returning(CurrentUserMetadataRequestBlockContext(rc, userMetadata, Set.empty, List.empty))
      .anyNumberOfTimes()
    (() => rc.action)
      .expects()
      .returning(Action.RorAction.RorUserMetadataAction)
      .anyNumberOfTimes()
    rc
  }

  private trait MetadataRequestContext extends RequestContext {
    override type BLOCK_CONTEXT = CurrentUserMetadataRequestBlockContext
  }
}
