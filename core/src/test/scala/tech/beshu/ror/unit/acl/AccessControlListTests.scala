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
import tech.beshu.ror.accesscontrol.AccessControl.UserMetadataRequestResult.Allow
import tech.beshu.ror.accesscontrol.acl.AccessControlList
import tech.beshu.ror.accesscontrol.acl.AccessControlList.AccessControlListStaticContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RegularRule
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.{FlsEngine, UsernameCaseMapping}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.UniqueList

class AccessControlListTests extends AnyWordSpec with MockFactory with Inside {

  "An AccessControlList" when {
    "metadata request is called" should {
      "go through all blocks and collect metadata response content" in {
        val blocks = NonEmptyList.of(
          mockBlock("b1", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroup(GroupName("admins")).withAvailableGroups(UniqueList.of(GroupName("logserver"), GroupName("ext-onlio"), GroupName("admins"), GroupName("ext-odp"), GroupName("ext-enex"), GroupName("dohled-nd-pce"), GroupName("helpdesk")))),
          mockBlock("b2", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroup(GroupName("admins")).withAvailableGroups(UniqueList.of(GroupName("logserver"), GroupName("ext-onlio"), GroupName("admins"), GroupName("ext-odp"), GroupName("ext-enex"), GroupName("dohled-nd-pce"), GroupName("helpdesk")))),
          mockBlock("b3", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroup(GroupName("admins")).withAvailableGroups(UniqueList.of(GroupName("logserver"), GroupName("ext-onlio"), GroupName("admins"), GroupName("ext-odp"), GroupName("ext-enex"), GroupName("dohled-nd-pce"), GroupName("helpdesk")))),
          mockBlock("b4", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroup(GroupName("admins")).withAvailableGroups(UniqueList.of(GroupName("logserver"), GroupName("ext-onlio"), GroupName("admins"), GroupName("ext-odp"), GroupName("ext-enex"), GroupName("dohled-nd-pce"), GroupName("helpdesk")))),
          mockBlock("b5", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroup(GroupName("admins")).withAvailableGroups(UniqueList.of(GroupName("logserver"), GroupName("ext-onlio"), GroupName("admins"), GroupName("ext-odp"), GroupName("ext-enex"), GroupName("dohled-nd-pce"), GroupName("helpdesk")))),
          mockBlock("b6", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroup(GroupName("admins")).withAvailableGroups(UniqueList.of(GroupName("logserver"), GroupName("ext-onlio"), GroupName("admins"), GroupName("ext-odp"), GroupName("ext-enex"), GroupName("dohled-nd-pce"), GroupName("helpdesk")))),
          mockBlock("b7", UserMetadata.empty.withLoggedUser(user("sulc1")).withCurrentGroup(GroupName("admins")).withAvailableGroups(UniqueList.of(GroupName("logserver"), GroupName("ext-onlio"), GroupName("admins"), GroupName("ext-odp"), GroupName("ext-enex"), GroupName("dohled-nd-pce"), GroupName("helpdesk")))),
        )
        val acl = new AccessControlList(
          blocks,
          new AccessControlListStaticContext(
            blocks,
            GlobalSettings(
              showBasicAuthPrompt = true,
              forbiddenRequestMessage = "Forbidden",
              flsEngine = FlsEngine.default,
              configurationIndex = RorConfigurationIndex(IndexName.Full(".readonlyrest")),
              usernameCaseMapping = UsernameCaseMapping.CaseSensitive
            ),
            Set.empty
          )
        )
        val userMetadataRequestResult = acl
          .handleMetadataRequest(mockMetadataRequestContext("admins"))
          .runSyncUnsafe()
          .result

        inside(userMetadataRequestResult) {
          case Allow(userMetadata, _) =>
            userMetadata.availableGroups shouldBe UniqueList.of(GroupName("logserver"), GroupName("ext-onlio"), GroupName("admins"), GroupName("ext-odp"), GroupName("ext-enex"), GroupName("dohled-nd-pce"), GroupName("helpdesk"))
            userMetadata.currentGroup shouldBe Some(GroupName("admins"))
        }
      }
    }
  }

  private def mockBlock(name: String, userMetadata: UserMetadata) = {
    new Block(
      Block.Name(name),
      Block.Policy.Allow,
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

  private def user(userName: String) =
    LoggedUser.DirectlyLoggedUser(User.Id(NonEmptyString.unsafeFrom(userName)))

  private def mockMetadataRequestContext(preferredGroup: String) = {
    val userMetadata = UserMetadata.empty.withCurrentGroup(GroupName(NonEmptyString.unsafeFrom(preferredGroup)))
    val rc = mock[MetadataRequestContext]
    (rc.initialBlockContext _)
      .expects()
      .returning(CurrentUserMetadataRequestBlockContext(rc, userMetadata, Set.empty, List.empty))
      .anyNumberOfTimes()
    (rc.action _)
      .expects()
      .returning(Action.rorUserMetadataAction)
      .anyNumberOfTimes()
    (rc.isReadOnlyRequest _)
      .expects()
      .returning(false)
      .anyNumberOfTimes()
    rc
  }

  private trait MetadataRequestContext extends RequestContext {
    override type BLOCK_CONTEXT = CurrentUserMetadataRequestBlockContext
  }
}
