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
import eu.timepit.refined.auto.*
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
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithGroupsMapping.Auth.{SeparateRules, SingleRule}
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
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.{SupportedVariablesFunctions, TransformationCompiler}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.ResolvableGroupsLogic.PositiveResolvableGroupsLogic
import tech.beshu.ror.accesscontrol.domain.User.UserIdPattern
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.concurrent.duration.*
import scala.language.postfixOps

trait BaseGroupsPositiveRuleTests[LOGIC <: PositiveResolvableGroupsLogic] extends AnyWordSpecLike with Inside with BlockContextAssertion {

  implicit val provider: EnvVarsProvider = OsEnvVarsProvider
  implicit val variableCreator: RuntimeResolvableVariableCreator =
    new RuntimeResolvableVariableCreator(TransformationCompiler.withAliases(SupportedVariablesFunctions.default, Seq.empty))

  implicit val logicCreator: ResolvableGroupIds => LOGIC
  def createRule(settings: GroupsRulesSettings[LOGIC], caseSensitivity: CaseSensitivity): BaseGroupsRule[LOGIC]

  // Common tests

  "An AbstractGroupsRule" should {
    "not match" when {
      "groups mapping is not configured" when {
        "no group can be resolved" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(
                createVariable("group_@{user}")(AlwaysRightConvertible.from(GroupIdLike.from)).toOption.get
              )),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("group_user1"))
              ))
            ),
            loggedUser = None,
            preferredGroupId = None
          )
        }
        "resolved groups don't contain preferred group" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupIdLike.from("g1").nel))),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1"))
              ))
            ),
            loggedUser = None,
            preferredGroupId = Some(GroupId("g2"))
          )
        }
        "there is no user definition for given logged user" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1"))
              ))
            ),
            loggedUser = Some(User.Id("user2")),
            preferredGroupId = None
          )
        }
        "there is no matching auth rule for given user" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1"))
              ))
            ),
            loggedUser = Some(User.Id("user1")),
            preferredGroupId = None
          )
        }
        "case sensitivity is configured, but authentication rule authenticates user with name with a capital letter at the beginning" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("u*"),
                mode = WithoutGroupsMapping(authenticationRule.matching(User.Id("User1")), groups("g1"))
              ))
            ),
            loggedUser = None,
            preferredGroupId = None
          )
        }
        "one auth rule available is throwing an exception" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.throwing, groups("g1"))
              ))
            ),
            loggedUser = Some(User.Id("user1")),
            preferredGroupId = None
          )
        }
      }
      "groups mapping is configured" when {
        "user cannot be authenticated by authentication with authorization rule" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(
                createVariable("group_@{user}")(AlwaysRightConvertible.from(GroupIdLike.from)).toOption.get
              )),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithGroupsMapping(SingleRule(authRule.rejecting), noGroupMappingFrom("group_user1"))
              ))
            ),
            loggedUser = None,
            preferredGroupId = None
          )
        }
        "user cannot be authorized by authentication with authorization rule" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(
                createVariable("group_@{user}")(AlwaysRightConvertible.from(GroupIdLike.from)).toOption.get
              )),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithGroupsMapping(
                  SeparateRules(
                    authenticationRule.matching(User.Id("user1")),
                    authorizationRule.rejecting
                  ),
                  noGroupMappingFrom("group_user1")
                )
              ))
            ),
            loggedUser = None,
            preferredGroupId = None
          )
        }
        "user cannot be authorized by authentication with authorization rule (simple groups mapping matching fails)" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(
                createVariable("group_@{user}")(AlwaysRightConvertible.from(GroupIdLike.from)).toOption.get
              )),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithGroupsMapping(
                  SeparateRules(
                    authenticationRule.matching(User.Id("user1")),
                    authorizationRule.matching(NonEmptyList.of(group("remote_group1")))
                  ),
                  noGroupMappingFrom("group_user1")
                )
              ))
            ),
            loggedUser = None,
            preferredGroupId = None
          )
        }
        "user cannot be authorized by authentication with authorization rule (advanced groups mapping matching fails)" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(
                createVariable("group_@{user}")(AlwaysRightConvertible.from(GroupIdLike.from)).toOption.get
              )),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithGroupsMapping(
                  SeparateRules(
                    authenticationRule.matching(User.Id("user1")),
                    authorizationRule.matching(NonEmptyList.of(group("remote_group2")))
                  ),
                  groupMapping(
                    Mapping(group("group_user1"), UniqueNonEmptyList.of(GroupIdLike.from("remote_group_1"))),
                    Mapping(group("group_user2"), UniqueNonEmptyList.of(GroupIdLike.from("remote_group_2")))
                  )
                )
              ))
            ),
            loggedUser = None,
            preferredGroupId = None
          )
        }
        "user cannot be authorized by authentication with authorization rule (advanced groups mapping with wildcard matching fails)" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(
                createVariable("group_@{user}")(AlwaysRightConvertible.from(GroupIdLike.from)).toOption.get
              )),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithGroupsMapping(
                  SeparateRules(
                    authenticationRule.matching(User.Id("user1")),
                    authorizationRule.matching(NonEmptyList.of(group("remote_group2")))
                  ),
                  groupMapping(
                    Mapping(group("group_user1"), UniqueNonEmptyList.of(GroupIdLike.from("*1"))),
                    Mapping(group("group_user2"), UniqueNonEmptyList.of(GroupIdLike.from("*2")))
                  )
                )
              ))
            ),
            loggedUser = None,
            preferredGroupId = None
          )
        }
      }
    }
    "match" when {
      "groups mapping is not configured" when {
        "user is logged in" when {
          "user ID is matched by user definition with full username" when {
            "authentication rule also matches and case sensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRulesSettings(
                  groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("user1")),
                      groups("g1")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroupId = None
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("user1"),
                  group = GroupId("g1"),
                  availableGroups = UniqueList.of(group("g1"))
                )
              )
            }
            "authentication rule also matches and case insensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRulesSettings(
                  groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("User1")),
                      groups("g1")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroupId = None,
                caseSensitivity = CaseSensitivity.Disabled
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("User1"),
                  group = GroupId("g1"),
                  availableGroups = UniqueList.of(group("g1"))
                )
              )
            }
          }
          "user ID is matched by user definition with username with wildcard" when {
            "authentication rule also matches and and case sensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRulesSettings(
                  groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("u*"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("user1")),
                      groups("g1")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroupId = None
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("user1"),
                  group = GroupId("g1"),
                  availableGroups = UniqueList.of(group("g1"))
                )
              )
            }
            "authentication rule also matches and and case insensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRulesSettings(
                  groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("u*"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("User1")),
                      groups("g1")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroupId = None,
                caseSensitivity = CaseSensitivity.Disabled
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("User1"),
                  group = GroupId("g1"),
                  availableGroups = UniqueList.of(group("g1"))
                )
              )
            }
          }
        }
        "user is not logged in" when {
          "user ID is matched by user definition with full username" when {
            "authentication rule also matches and preferred group is used" in {
              assertMatchRule(
                settings = GroupsRulesSettings(
                  groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                  usersDefinitions = NonEmptyList.of(
                    UserDef(
                      usernames = userIdPatterns("user2"),
                      mode = WithoutGroupsMapping(
                        authenticationRule.rejecting,
                        groups("g1", "g2")
                      )
                    ),
                    UserDef(
                      usernames = userIdPatterns("user1"),
                      mode = WithoutGroupsMapping(
                        authenticationRule.matching(User.Id("user1")),
                        groups("g1")
                      )
                    )
                  )
                ),
                loggedUser = None,
                preferredGroupId = Some(GroupId("g1"))
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("user1"),
                  group = GroupId("g1"),
                  availableGroups = UniqueList.of(group("g1"))
                )
              )
            }
          }
        }
      }
      "groups mapping is configured" when {
        "one authentication with authorization rule is used" when {
          "user can be matched and user can be authorized in external system and locally (simple groups mapping)" in {
            assertMatchRule(
              settings = GroupsRulesSettings(
                groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                usersDefinitions = NonEmptyList.of(
                  UserDef(
                    usernames = userIdPatterns("user2"),
                    mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1", "g2"))
                  ),
                  UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithGroupsMapping(
                      SingleRule(authRule.matching(User.Id("user1"), NonEmptyList.of(group("remote_group")))),
                      noGroupMappingFrom("g1")
                    )
                  )
                )
              ),
              loggedUser = None,
              preferredGroupId = None
            )(
              blockContextAssertion = defaultOutputBlockContextAssertion(
                user = User.Id("user1"),
                group = GroupId("g1"),
                availableGroups = UniqueList.of(group("g1"))
              )
            )
          }
          "user can be matched and user can be authorized in external system and locally (advanced groups mapping)" in {
            assertMatchRule(
              settings = GroupsRulesSettings(
                groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                usersDefinitions = NonEmptyList.of(
                  UserDef(
                    usernames = userIdPatterns("user2"),
                    mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1", "g2"))
                  ),
                  UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithGroupsMapping(
                      SingleRule(authRule.matching(User.Id("user1"), NonEmptyList.of(group("remote_group")))),
                      groupMapping(Mapping(group("g1"), UniqueNonEmptyList.of(GroupIdLike.from("remote_group"))))
                    )
                  )
                )
              ),
              loggedUser = None,
              preferredGroupId = None
            )(
              blockContextAssertion = defaultOutputBlockContextAssertion(
                user = User.Id("user1"),
                group = GroupId("g1"),
                availableGroups = UniqueList.of(group("g1"))
              )
            )
          }
          "user can be matched and user can be authorized in external system and locally (advanced groups mapping with wildcard)" in {
            assertMatchRule(
              settings = GroupsRulesSettings(
                groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                usersDefinitions = NonEmptyList.of(
                  UserDef(
                    usernames = userIdPatterns("user2"),
                    mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1", "g2"))
                  ),
                  UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithGroupsMapping(
                      SingleRule(authRule.matching(User.Id("user1"), NonEmptyList.of(group("remote_group_1")))),
                      groupMapping(Mapping(group("g1"), UniqueNonEmptyList.of(GroupIdLike.from("*1"))))
                    )
                  )
                )
              ),
              loggedUser = None,
              preferredGroupId = None
            )(
              blockContextAssertion = defaultOutputBlockContextAssertion(
                user = User.Id("user1"),
                group = GroupId("g1"),
                availableGroups = UniqueList.of(group("g1"))
              )
            )
          }
        }
        "separate authentication and authorization rules are used" when {
          "user can be matched and user can be authorized in external system and locally" in {
            assertMatchRule(
              settings = GroupsRulesSettings(
                groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                usersDefinitions = NonEmptyList.of(
                  UserDef(
                    usernames = userIdPatterns("user2"),
                    mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1", "g2"))
                  ),
                  UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithGroupsMapping(
                      SeparateRules(
                        authenticationRule.matching(User.Id("user1")),
                        authorizationRule.matching(NonEmptyList.of(group("remote_group")))
                      ),
                      noGroupMappingFrom("g1")
                    )
                  )
                )
              ),
              loggedUser = None,
              preferredGroupId = None
            )(
              blockContextAssertion = defaultOutputBlockContextAssertion(
                user = User.Id("user1"),
                group = GroupId("g1"),
                availableGroups = UniqueList.of(group("g1"))
              )
            )
          }
        }
      }
    }
  }

  def assertMatchRule(settings: GroupsRulesSettings[LOGIC],
                      loggedUser: Option[User.Id],
                      preferredGroupId: Option[GroupId],
                      caseSensitivity: CaseSensitivity = CaseSensitivity.Enabled)
                     (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(settings, loggedUser, preferredGroupId, Some(blockContextAssertion), caseSensitivity)

  def assertNotMatchRule(settings: GroupsRulesSettings[LOGIC],
                         loggedUser: Option[User.Id],
                         preferredGroupId: Option[GroupId],
                         caseSensitivity: CaseSensitivity = CaseSensitivity.Enabled): Unit =
    assertRule(settings, loggedUser, preferredGroupId, blockContextAssertion = None, caseSensitivity)

  def assertRule(settings: GroupsRulesSettings[LOGIC],
                 loggedUser: Option[User.Id],
                 preferredGroupId: Option[GroupId],
                 blockContextAssertion: Option[BlockContext => Unit],
                 caseSensitivity: CaseSensitivity): Unit = {
    val rule = createRule(settings, caseSensitivity)
    val requestContext = MockRequestContext.metadata.copy(
      headers = preferredGroupId.map(_.toCurrentGroupHeader).toCovariantSet
    )
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

  private def createVariable[T: Convertible](text: NonEmptyString) = {
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
