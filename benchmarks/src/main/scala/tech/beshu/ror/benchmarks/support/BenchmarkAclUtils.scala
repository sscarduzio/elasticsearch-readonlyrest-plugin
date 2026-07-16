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
package tech.beshu.ror.benchmarks.support

import cats.data.NonEmptyList
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.accesscontrol.AccessControlList.RegularRequestResult
import tech.beshu.ror.accesscontrol.EnabledAccessControlList
import tech.beshu.ror.accesscontrol.EnabledAccessControlList.AccessControlListStaticContext
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.blocks.Decision
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseGroupsRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{AnyOfGroupsRule, AuthKeyRule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableGroupsLogic}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.benchmarks.support.BenchmarkSupport.nes
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

/**
 * ACL-object creation and assertion helpers shared by the benchmark setup code,
 * so each benchmark class reads as: setup -> benchmarks -> its own private utils.
 */
object BenchmarkAclUtils {

  def createAcl(blocks: NonEmptyList[Block]): EnabledAccessControlList = {
    new EnabledAccessControlList(
      blocks,
      new AccessControlListStaticContext(
        blocks,
        GlobalSettings(
          showBasicAuthPrompt = false,
          forbiddenRequestMessage = "forbidden",
          flsEngine = GlobalSettings.FlsEngine.ESWithLucene,
          settingsIndex = RorSettingsIndex(IndexName.Full(nes(".readonlyrest"))),
          userIdCaseSensitivity = CaseSensitivity.Enabled,
          usersDefinitionDuplicateUsernamesValidationEnabled = false
        ),
        Set.empty
      )
    )
  }

  def createCredentials(user: String, password: String): Credentials =
    Credentials(User.Id(nes(user)), PlainTextSecret(nes(password)))

  def createAuthKeyRule(user: String, password: String): AuthKeyRule =
    new AuthKeyRule(
      BasicAuthenticationRule.Settings(createCredentials(user, password)),
      CaseSensitivity.Enabled,
      Impersonation.Disabled
    )

  def createGroupId(idx: Int): GroupId = GroupId(nes(s"dept-$idx"))

  def createGroup(idx: Int): Group = Group.from(createGroupId(idx))

  def createUserDef(user: String, password: String, userGroupCount: Int): UserDef =
    UserDef(
      usernames = UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(User.Id(nes(user))))),
      mode = UserDef.Mode.WithoutGroupsMapping(
        createAuthKeyRule(user, password),
        UniqueNonEmptyList.unsafeFrom((0 until userGroupCount).map(createGroup))
      )
    )

  def createAnyOfGroupsRule(user: String,
                            password: String,
                            configuredGroupCount: Int,
                            userGroupCount: Int): AnyOfGroupsRule = {
    val permittedGroupIds = UniqueNonEmptyList.unsafeFrom(
      (0 until configuredGroupCount)
        .map(idx => AlreadyResolved(NonEmptyList.one(createGroupId(idx): GroupIdLike)): RuntimeMultiResolvableVariable[GroupIdLike])
    )
    new AnyOfGroupsRule(
      BaseGroupsRule.Settings(
        new RuntimeResolvableGroupsLogic.Simple[GroupsLogic.AnyOf](permittedGroupIds),
        NonEmptyList.one(createUserDef(user, password, userGroupCount))
      )
    )(CaseSensitivity.Enabled)
  }

  def assertRequestAllowedBy[B <: BlockContext : BlockContextUpdater](acl: EnabledAccessControlList,
                                                                      context: RequestContext.Aux[B],
                                                                      expectedBlockName: String): Unit = {
    val result = acl.handleRegularRequest(context).runSyncUnsafe()._1
    require(
      result match {
        case RegularRequestResult.Allowed(blockContext) => blockContext.block.name.value == expectedBlockName
        case _ => false
      },
      s"expected the request to be allowed by '$expectedBlockName', got: $result"
    )
  }

  def assertRequestForbiddenBy[B <: BlockContext : BlockContextUpdater](acl: EnabledAccessControlList,
                                                                        context: RequestContext.Aux[B]): Unit = {
    val result = acl.handleRegularRequest(context).runSyncUnsafe()._1
    require(
      result.isInstanceOf[RegularRequestResult.ForbiddenByMismatched[?]],
      s"expected the request to be forbidden, got: $result"
    )
  }

  def assertRulePermitted(decision: Decision[?]): Unit =
    require(decision.isInstanceOf[Decision.Permitted[?]], s"expected the rule to permit, got: $decision")

  def assertRuleDenied(decision: Decision[?]): Unit =
    require(decision.isInstanceOf[Decision.Denied[?]], s"expected the rule to deny, got: $decision")
}
