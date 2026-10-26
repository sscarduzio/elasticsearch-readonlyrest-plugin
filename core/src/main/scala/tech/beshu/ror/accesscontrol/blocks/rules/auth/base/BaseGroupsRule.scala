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
package tech.beshu.ror.accesscontrol.blocks.rules.auth.base

import cats.data.{EitherT, NonEmptyList}
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.{AuthenticationFailed, GroupsAuthorizationFailed}
import tech.beshu.ror.accesscontrol.blocks.Decision.{Denied, Permitted}
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithGroupsMapping.Auth
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.{GroupMappings, Mode}
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.*
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseGroupsRule.{Settings, UserDefFailure}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{
  AuthenticationImpersonationCustomSupport,
  AuthorizationImpersonationCustomSupport
}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableGroupsLogic
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.matchers.GenericPatternMatcher
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

abstract class BaseGroupsRule[+GL <: GroupsLogic](override val name: Rule.Name, val settings: Settings[GL])(
    override implicit val userIdCaseSensitivity: CaseSensitivity
) extends AuthRule
    with AuthenticationImpersonationCustomSupport
    with AuthorizationImpersonationCustomSupport
    with RequestIdAwareLogging {

  override val localUsers: LocalUsers =
    settings.usersDefinitions.toList
      .map(_.authenticationRule.localUsers)
      .combineAll

  private val matchers = settings.usersDefinitions.toList.map { userDef =>
    userDef -> new GenericPatternMatcher(userDef.usernames.patterns.toList)
  }.toMap

  override protected def authenticate[B <: BlockContext: BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
    Task.defer {
      resolveGroupsLogic(blockContext) match {
        case Some(permittedGroupsLogic) if blockContext.isCurrentGroupPotentiallyEligible(permittedGroupsLogic) =>
          continueCheckingWithUserDefinitions(blockContext, permittedGroupsLogic)
        case Some(_) =>
          rejectWithGroupsAuthorizationFailure("Current group is not allowed")
        case None =>
          rejectWithGroupsAuthorizationFailure("No resolved allowed groups")
      }
    }
  }

  override protected def authorize[B <: BlockContext: BlockContextUpdater](blockContext: B): Task[Decision[B]] =
    Task.now(Decision.Permitted(blockContext))

  private def continueCheckingWithUserDefinitions[B <: BlockContext: BlockContextUpdater](
      blockContext: B,
      permittedGroupsLogic: GroupsLogic
  ): Task[Decision[B]] = {
    blockContext.blockMetadata.loggedUser match {
      case Some(user) =>
        NonEmptyList.fromFoldable(userDefinitionsMatching(user.id)) match {
          case None =>
            rejectWithGroupsAuthorizationFailure(s"User '${user.id.show}' not in allowed users list")
          case Some(filteredUserDefinitions) =>
            tryToAuthorizeAndAuthenticateUsing(filteredUserDefinitions, blockContext, permittedGroupsLogic)
        }
      case None =>
        tryToAuthorizeAndAuthenticateUsing(settings.usersDefinitions, blockContext, permittedGroupsLogic)
    }
  }

  private def userDefinitionsMatching(userId: User.Id) = {
    settings.usersDefinitions.filter(ud => matchers(ud).`match`(userId))
  }

  private def tryToAuthorizeAndAuthenticateUsing[B <: BlockContext: BlockContextUpdater](
      userDefs: NonEmptyList[UserDef],
      blockContext: B,
      permittedGroupsLogic: GroupsLogic
  ): Task[Decision[B]] = {
    implicit val blockContextImpl: B = blockContext

    def loop(
        remaining: List[UserDef],
        failures: List[(UserDef, UserDefFailure)]
    ): Task[(List[(UserDef, UserDefFailure)], Option[B])] = {
      remaining match {
        case Nil =>
          Task.now((failures.reverse, None))
        case userDef :: tail =>
          authorizeAndAuthenticate(blockContext, permittedGroupsLogic)(userDef).flatMap {
            case Right(successContext) =>
              Task.now((failures.reverse, Some(successContext)))
            case Left(failure) =>
              loop(tail, (userDef -> failure) :: failures)
          }
      }
    }

    loop(userDefs.toList, Nil)
      .map { case (failures, result) =>
        logInnerRuleErrors(failures.flatMap(_._2.innerRuleError))
        result match {
          case Some(newBlockContext) =>
            Permitted(newBlockContext)
          case None =>
            val details = CauseFormatting.formatGroupedCauses(failures.map { case (userDef, failure) =>
              (userDef.usernames, failure.cause)
            })
            Denied(GroupsAuthorizationFailed(details))
        }
      }
  }

  private def authorizeAndAuthenticate[B <: BlockContext: BlockContextUpdater](
      blockContext: B,
      permittedGroupsLogic: GroupsLogic
  )(userDef: UserDef): Task[Either[UserDefFailure, B]] = {
    permittedGroupsLogic.availableGroupsFrom(userDef.localGroups) match {
      case Some(availableGroups) if blockContext.isCurrentGroupEligible(GroupIds.from(availableGroups)) =>
        val allowedUserMatcher = matchers(userDef)
        userDef.mode match {
          case Mode.WithoutGroupsMapping(auth, _) =>
            authenticate(
              authnRule = auth,
              blockContext,
              allowedUserMatcher,
              availableGroups,
              userDef.mode
            )
          case Mode.WithGroupsMapping(Auth.SingleRule(auth), groupMappings) =>
            authenticateAndAuthorize(
              authRule = auth,
              groupMappings,
              blockContext,
              allowedUserMatcher,
              availableGroups,
              userDef.mode
            )
          case Mode.WithGroupsMapping(Auth.SeparateRules(authn, authz), groupMappings) =>
            authenticateAndAuthorize(
              authnRule = authn,
              authzRule = authz,
              groupMappings,
              blockContext,
              allowedUserMatcher,
              availableGroups,
              userDef.mode
            )
        }
      case Some(_) =>
        Task.now(Left(UserDefFailure(GroupsAuthorizationFailed("Current group is not allowed"))))
      case None =>
        Task.now(Left(UserDefFailure(GroupsAuthorizationFailed("No user's groups allowed"))))
    }
  }

  private def authenticate[B <: BlockContext: BlockContextUpdater](
      authnRule: AuthenticationRule,
      blockContext: B,
      allowedUserMatcher: GenericPatternMatcher[User.Id],
      availableGroups: UniqueNonEmptyList[Group],
      mode: Mode
  ): Task[Either[UserDefFailure, B]] = {
    val result = for {
      newBlockContext <- checkRule(authnRule, blockContext, mode)
      fullyUpdatedBlockContext <- updateBlockContextWithLoggedUser(
        sourceBlockContext = newBlockContext,
        destinationBlockContext = blockContext,
        allowedUserMatcher = allowedUserMatcher
      )
    } yield fullyUpdatedBlockContext.withBlockMetadata(_.withAvailableGroups(UniqueList.from(availableGroups)))
    result.value
  }

  private def authenticateAndAuthorize[B <: BlockContext: BlockContextUpdater](
      authRule: AuthRule,
      groupMappings: GroupMappings,
      blockContext: B,
      allowedUserMatcher: GenericPatternMatcher[User.Id],
      availableGroups: UniqueNonEmptyList[Group],
      mode: Mode
  ): Task[Either[UserDefFailure, B]] = {
    val result = for {
      newBlockContext <- checkRule(authRule, blockContext, mode)
      updatedBlockContextWithAuthnData <- updateBlockContextWithLoggedUser(
        sourceBlockContext = newBlockContext,
        destinationBlockContext = blockContext,
        allowedUserMatcher = allowedUserMatcher
      )
      fullyUpdatedBlockContext <- updateBlockContextWithAllowedGroups(
        sourceBlockContext = newBlockContext,
        destinationBlockContext = updatedBlockContextWithAuthnData,
        potentiallyAvailableGroups = availableGroups,
        groupMappings = groupMappings
      )
    } yield fullyUpdatedBlockContext
    result.value
  }

  private def authenticateAndAuthorize[B <: BlockContext: BlockContextUpdater](
      authnRule: AuthenticationRule,
      authzRule: AuthorizationRule,
      groupMappings: GroupMappings,
      blockContext: B,
      allowedUserMatcher: GenericPatternMatcher[User.Id],
      availableGroups: UniqueNonEmptyList[Group],
      mode: Mode
  ): Task[Either[UserDefFailure, B]] = {
    val result = for {
      blockContextAfterAuthn <- checkRule(authnRule, blockContext, mode)
      updatedBlockContextAfterAuthn <- updateBlockContextWithLoggedUser(
        blockContextAfterAuthn,
        blockContext,
        allowedUserMatcher
      )
      blockContextAfterAuthz <- checkRuleRecoveringError(authzRule, updatedBlockContextAfterAuthn)
      updatedBlockContextAfterAuthz <- updateBlockContextWithAllowedGroups(
        blockContextAfterAuthz,
        updatedBlockContextAfterAuthn,
        availableGroups,
        groupMappings
      )
    } yield updatedBlockContextAfterAuthz
    result.value
  }

  private def updateBlockContextWithLoggedUser[B <: BlockContext: BlockContextUpdater](
      sourceBlockContext: B,
      destinationBlockContext: B,
      allowedUserMatcher: GenericPatternMatcher[User.Id]
  ): EitherT[Task, UserDefFailure, B] = {
    EitherT.fromEither[Task] {
      sourceBlockContext.blockMetadata.loggedUser match {
        case Some(user) if allowedUserMatcher.`match`(user.id) =>
          Right(destinationBlockContext.withBlockMetadata(_.withLoggedUser(user)))
        case Some(user) =>
          Left(UserDefFailure(AuthenticationFailed(s"Logged user doesn't match allowed patterns")))
        case None =>
          Left(UserDefFailure(AuthenticationFailed("No logged user found after authentication")))
      }
    }
  }

  private def updateBlockContextWithAllowedGroups[B <: BlockContext: BlockContextUpdater](
      sourceBlockContext: B,
      destinationBlockContext: B,
      potentiallyAvailableGroups: UniqueNonEmptyList[Group],
      groupMappings: GroupMappings
  ): EitherT[Task, UserDefFailure, B] = {
    EitherT.fromEither[Task] {
      val externalAvailableGroups = sourceBlockContext.blockMetadata.availableGroups
      (for {
        externalGroupsMappedToLocalGroups <- mapExternalGroupsToLocalGroups(groupMappings, externalAvailableGroups)
        availableLocalGroups <- availableLocalGroupsFromExternalGroupsMappedToLocalGroups(
          externalGroupsMappedToLocalGroups,
          potentiallyAvailableGroups
        )
      } yield {
        def requiredData: BlockMetadata => BlockMetadata = { metadata =>
          metadata.withAvailableGroups(UniqueList.from(availableLocalGroups))
        }

        def optionalUserOrigin: BlockMetadata => BlockMetadata = { metadata =>
          sourceBlockContext.blockMetadata.userOrigin.map(metadata.withUserOrigin).getOrElse(metadata)
        }

        def optionalJwtToken: BlockMetadata => BlockMetadata = { metadata =>
          sourceBlockContext.blockMetadata.jwtToken.map(metadata.withJwtToken).getOrElse(metadata)
        }

        destinationBlockContext
          .withBlockMetadata { metadata =>
            (requiredData :: optionalUserOrigin :: optionalJwtToken :: Nil)
              .foldLeft(metadata) { case (acc, func) => func(acc) }
          }
      }).left.map(UserDefFailure(_))
    }
  }

  private def availableLocalGroupsFromExternalGroupsMappedToLocalGroups(
      externalGroupsMappedToLocalGroups: UniqueNonEmptyList[GroupIdLike],
      potentiallyAvailableGroups: UniqueNonEmptyList[Group]
  ) = {
    val potentiallyPermitted = GroupIds(externalGroupsMappedToLocalGroups)
    UniqueNonEmptyList
      .from(potentiallyPermitted.filterOnlyPermitted(potentiallyAvailableGroups))
      .toRight(
        GroupsAuthorizationFailed(
          "None of the user's external groups could be mapped to any of the locally permitted groups"
        )
      )
  }

  private def checkRule[B <: BlockContext: BlockContextUpdater](rule: Rule, blockContext: B, mode: Mode) = {
    val initialBlockContext = mode match {
      case Mode.WithGroupsMapping(_, _)    => blockContext.withBlockMetadata(_.clearCurrentGroup)
      case Mode.WithoutGroupsMapping(_, _) => blockContext
    }
    checkRuleRecoveringError(rule, initialBlockContext)
  }

  // An error of one user definition's rule must not stop the check of the next user definitions
  private def checkRuleRecoveringError[B <: BlockContext: BlockContextUpdater](
      rule: Rule,
      blockContext: B
  ): EitherT[Task, UserDefFailure, B] = EitherT {
    implicit val blockContextImpl: B = blockContext
    Task
      .defer(rule.check(blockContext))
      .map {
        case fulfilled: Decision.Permitted[B] => Right(fulfilled.context)
        case Decision.Denied(cause)           => Left(UserDefFailure(cause))
      }
      .onErrorRecover { case ex =>
        logger.debug(s"${name.show}: ${rule.name.show} rule matching got an error ${ex.getMessage}", ex)
        Left(UserDefFailure(Cause.ruleError(rule), Some(UserDefFailure.InnerRuleError(rule.name, ex))))
      }
  }

  private def logInnerRuleErrors(errors: List[UserDefFailure.InnerRuleError])(
      implicit id: RequestId
  ): Unit = errors match {
    case Nil             =>
    case firstError :: _ =>
      val details = errors
        .map(e => s"${e.rule.show}: ${Option(e.error.getMessage).getOrElse(e.error.getClass.getSimpleName)}")
        .mkString("; ")
      logger.error(s"${name.show}: inner rule matching got errors [$details]", firstError.error)
  }

  private def mapExternalGroupsToLocalGroups(groupMappings: GroupMappings, externalGroup: UniqueList[Group]) = {
    groupMappings match {
      case GroupMappings.Simple(localGroups) =>
        Right(UniqueNonEmptyList.unsafeFrom(localGroups.map(_.id)))
      case GroupMappings.Advanced(mappings) =>
        UniqueNonEmptyList
          .from {
            externalGroup.toList
              .flatMap { externalGroup =>
                mappings.toList
                  .filter(m => m.externalGroupIdPatternsMatcher.`match`(externalGroup.id))
                  .map(_.local)
              }
              .map(_.id)
          }
          .toRight(GroupsAuthorizationFailed("No external groups matched any mapping pattern"))
    }
  }

  private def resolveGroupsLogic[B <: BlockContext](blockContext: B) = {
    settings.permittedGroupsLogic.resolve(blockContext)
  }

  private def rejectWithGroupsAuthorizationFailure[T](details: String) =
    Task.now(Decision.deny[T](Cause.GroupsAuthorizationFailed(details)))

}

object BaseGroupsRule {

  final case class Settings[+GL <: GroupsLogic](
      permittedGroupsLogic: RuntimeResolvableGroupsLogic[GL],
      usersDefinitions: NonEmptyList[UserDef]
  )

  trait Creator[GL <: GroupsLogic] {
    def create(settings: Settings[GL], userIdCaseSensitivity: CaseSensitivity): BaseGroupsRule[GL]
  }

  object Creator {

    def apply[GL <: GroupsLogic](
        implicit creator: Creator[GL]
    ): Creator[GL] = creator

  }

  private final case class UserDefFailure(cause: Cause, innerRuleError: Option[UserDefFailure.InnerRuleError])

  private object UserDefFailure {
    final case class InnerRuleError(rule: Rule.Name, error: Throwable)

    def apply(cause: Cause): UserDefFailure = UserDefFailure(cause, None)
  }

}
