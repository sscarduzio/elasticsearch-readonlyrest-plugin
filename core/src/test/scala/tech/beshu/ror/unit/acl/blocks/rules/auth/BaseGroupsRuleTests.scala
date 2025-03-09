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
package tech.beshu.ror.unit.acl.blocks.rules.auth

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpecLike
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.CurrentUserMetadataRequestBlockContextUpdater
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.GroupMappings.Advanced.Mapping
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithGroupsMapping.Auth.SingleRule
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.{WithGroupsMapping, WithoutGroupsMapping}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.*
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.BaseGroupsRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.BaseGroupsRule.Settings as GroupsRulesSettings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{AuthenticationImpersonationCustomSupport, AuthorizationImpersonationCustomSupport}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableGroupsLogic, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.{SupportedVariablesFunctions, TransformationCompiler}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.User.UserIdPattern
import tech.beshu.ror.mocks.{MockRequestContext, MockRestRequest}
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.concurrent.duration.*
import scala.language.postfixOps

trait BaseGroupsRuleTests[GL <: GroupsLogic] extends AnyWordSpecLike with Inside with BlockContextAssertion {

  implicit val provider: EnvVarsProvider = OsEnvVarsProvider
  implicit val variableCreator: RuntimeResolvableVariableCreator =
    new RuntimeResolvableVariableCreator(TransformationCompiler.withAliases(SupportedVariablesFunctions.default, Seq.empty))

  protected def groupsLogicCreator: GroupIds => GL

  protected def createRule(settings: GroupsRulesSettings[GL], caseSensitivity: CaseSensitivity): BaseGroupsRule[GL]

  protected final def resolvableGroupsLogic(groupIds: UniqueNonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]]): RuntimeResolvableGroupsLogic[GL] = {
    RuntimeResolvableGroupsLogic(groupIds, groupsLogicCreator)
  }

  // Common tests

  "An AbstractGroupsRule" should {
    "not match because of not eligible preferred group present" when {
      "groups mapping is not configured" in {
        val ruleSettings = GroupsRulesSettings(
          permittedGroupsLogic = resolvableGroupsLogic(UniqueNonEmptyList.of(
            AlreadyResolved(GroupId("g1").nel),
            AlreadyResolved(GroupId("g2").nel),
          )),
          usersDefinitions = NonEmptyList.of(UserDef(
            usernames = userIdPatterns("user1"),
            mode = WithoutGroupsMapping(
              authenticationRule.matching(User.Id("user1")),
              groups("g1")
            )
          ))
        )
        val usr = Some(User.Id("user1"))
        assertNotMatchRule(
          settings = ruleSettings,
          loggedUser = usr,
          caseSensitivity = CaseSensitivity.Disabled,
          preferredGroupId = Some(GroupId("g3"))
        )
      }
      "groups mapping is configured" in {
        val ruleSettings = GroupsRulesSettings(
          permittedGroupsLogic = resolvableGroupsLogic(UniqueNonEmptyList.of(
            AlreadyResolved(GroupId("g1").nel),
            AlreadyResolved(GroupId("g2").nel),
          )),
          usersDefinitions = NonEmptyList.of(UserDef(
            usernames = userIdPatterns("user1"),
            mode = WithGroupsMapping(
              SingleRule(authRule.matching(User.Id("user1"), NonEmptyList.of(group("remote_group")))),
              groupMapping(Mapping(group("g1"), UniqueNonEmptyList.of(GroupIdLike.from("remote_group"))))
            )
          ))
        )
        val usr = Some(User.Id("user1"))
        assertNotMatchRule(
          settings = ruleSettings,
          loggedUser = usr,
          caseSensitivity = CaseSensitivity.Disabled,
          preferredGroupId = Some(GroupId("g3"))
        )
      }
    }
  }

  def assertMatchRule(settings: GroupsRulesSettings[GL],
                      loggedUser: Option[User.Id],
                      preferredGroupId: Option[GroupId],
                      caseSensitivity: CaseSensitivity = CaseSensitivity.Enabled)
                     (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(settings, loggedUser, preferredGroupId, Some(blockContextAssertion), caseSensitivity)

  def assertNotMatchRule(settings: GroupsRulesSettings[GL],
                         loggedUser: Option[User.Id],
                         preferredGroupId: Option[GroupId],
                         caseSensitivity: CaseSensitivity = CaseSensitivity.Enabled): Unit =
    assertRule(settings, loggedUser, preferredGroupId, blockContextAssertion = None, caseSensitivity)

  def assertRule(settings: GroupsRulesSettings[GL],
                 loggedUser: Option[User.Id],
                 preferredGroupId: Option[GroupId],
                 blockContextAssertion: Option[BlockContext => Unit],
                 caseSensitivity: CaseSensitivity): Unit = {
    val rule = createRule(settings, caseSensitivity)
    val requestContext = MockRequestContext.metadata.copy(restRequest = MockRestRequest(
      allHeaders = preferredGroupId.map(_.toCurrentGroupHeader).toCovariantSet,
      path = UriPath.auditEventPath
    ))
    val blockContext = CurrentUserMetadataRequestBlockContext(
      requestContext,
      loggedUser match {
        case Some(user) => UserMetadata.from(requestContext).withLoggedUser(DirectlyLoggedUser(user))
        case None => UserMetadata.from(requestContext)
      },
      Set.empty,
      List.empty
    )
    val result = rule.check(blockContext).runSyncUnsafe(1 second)
    blockContextAssertion match {
      case Some(assertOutputBlockContext) =>
        inside(result) { case Fulfilled(outBlockContext) =>
          assertOutputBlockContext(outBlockContext)
        }
      case None =>
        result should be(Rejected())
    }
  }

  def userIdPatterns(id: String, ids: String*): UserIdPatterns = {
    UserIdPatterns(
      UniqueNonEmptyList.unsafeFrom(
        (id :: ids.toList).map(str => UserIdPattern(User.Id(NonEmptyString.unsafeFrom(str))))
      )
    )
  }

  def groups(g1: String, gs: String*): UniqueNonEmptyList[Group] = {
    UniqueNonEmptyList.of(group(g1), gs.map(group): _*)
  }

  def defaultOutputBlockContextAssertion(user: User.Id,
                                         group: GroupId,
                                         availableGroups: UniqueList[Group]): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(
        loggedUser = Some(DirectlyLoggedUser(user)),
        currentGroup = Some(group),
        availableGroups = availableGroups
      )(blockContext)
    }

  protected def createVariable[T: Convertible](text: NonEmptyString): Either[RuntimeResolvableVariableCreator.CreationError, RuntimeMultiResolvableVariable[T]] = {
    variableCreator.createMultiResolvableVariableFrom[T](text)
  }

  object authenticationRule {

    def matching(user: User.Id): AuthenticationRule = new AuthenticationRule with AuthenticationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name("dummy-fulfilling")
      override implicit val userIdCaseSensitivity: CaseSensitivity = CaseSensitivity.Enabled
      override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable

      override protected def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
        Task.now(Fulfilled(blockContext.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(user)))))
    }

    val rejecting: AuthenticationRule = new AuthenticationRule with AuthenticationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name("dummy-rejecting")
      override implicit val userIdCaseSensitivity: CaseSensitivity = CaseSensitivity.Enabled
      override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable

      override protected def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
        Task.now(Rejected())
    }

    val throwing: AuthenticationRule = new AuthenticationRule with AuthenticationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name("dummy-throwing")
      override implicit val userIdCaseSensitivity: CaseSensitivity = CaseSensitivity.Enabled
      override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable

      override protected def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
        Task.raiseError(new Exception("Sth went wrong"))
    }
  }

  object authorizationRule {

    def matching(groups: NonEmptyList[Group]): AuthorizationRule = new AuthorizationRule with AuthorizationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name("dummy-fulfilling")

      override protected def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
        Task.now(Fulfilled(blockContext.withUserMetadata(
          _.withAvailableGroups(UniqueList.from(groups.toList))
        )))
      }
    }

    val rejecting: AuthorizationRule = new AuthorizationRule with AuthorizationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name("dummy-rejecting")

      override protected def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
        Task.now(Rejected())
    }
  }

  object authRule {

    def matching(user: User.Id, groups: NonEmptyList[Group]): AuthRule = new AuthRule with AuthenticationRule with AuthorizationRule with AuthorizationImpersonationCustomSupport with AuthenticationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name("dummy-fulfilling")

      override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable
      override implicit val userIdCaseSensitivity: CaseSensitivity = CaseSensitivity.Enabled

      override protected def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
        Task.now(Fulfilled(blockContext.withUserMetadata(
          _.withLoggedUser(DirectlyLoggedUser(user))
        )))

      override protected def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
        Task.now(Fulfilled(blockContext.withUserMetadata(
          _.withAvailableGroups(UniqueList.from(groups.toList))
        )))
    }

    val rejecting: AuthRule = new AuthRule with AuthenticationRule with AuthorizationRule with AuthorizationImpersonationCustomSupport with AuthenticationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name("dummy-rejecting")

      override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable
      override implicit val userIdCaseSensitivity: CaseSensitivity = CaseSensitivity.Enabled

      override protected def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
        Task.now(Rejected())

      override protected def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
        Task.now(Rejected())
    }
  }
}
