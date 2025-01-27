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
package tech.beshu.ror.accesscontrol.factory.decoders.rules.auth

import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.*
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{LdapAuthRule, LdapAuthenticationRule, LdapAuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, GroupsLogic}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common.*
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.LdapServicesDecoder.nameDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.{Definitions, LdapServicesDecoder}
import tech.beshu.ror.accesscontrol.factory.decoders.rules.OptionalImpersonatorDefinitionOps
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.LdapRulesDecodersHelper.*
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration

// ------ ldap_authentication
class LdapAuthenticationRuleDecoder(ldapDefinitions: Definitions[LdapService],
                                    impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                                    mocksProvider: MocksProvider,
                                    globalSettings: GlobalSettings)
  extends RuleBaseDecoderWithoutAssociatedFields[LdapAuthenticationRule] {

  import LdapRulesDecodersHelper.*

  override protected def decoder: Decoder[RuleDefinition[LdapAuthenticationRule]] = {
    simpleLdapAuthenticationNameAndLocalConfig
      .orElse(complexLdapAuthenticationServiceNameAndLocalConfig)
      .toSyncDecoder
      .emapE { case (name, ttl) =>
        LdapRulesDecodersHelper
          .findLdapService[LdapAuthenticationRule](LdapServiceType.Authentication, name, ldapDefinitions.items)
          .map(CacheableLdapAuthenticationServiceDecorator.createWithCacheableLdapUsersService(_, ttl))
      }
      .map(new LoggableLdapAuthenticationServiceDecorator(_))
      .map(service => RuleDefinition.create(
        new LdapAuthenticationRule(
          LdapAuthenticationRule.Settings(service),
          globalSettings.userIdCaseSensitivity,
          impersonatorsDef.toImpersonation(mocksProvider),
        )
      ))
      .decoder
  }

  private def simpleLdapAuthenticationNameAndLocalConfig: Decoder[(LdapService.Name, Option[PositiveFiniteDuration])] =
    LdapServicesDecoder.nameDecoder
      .map((_, None))

  private def complexLdapAuthenticationServiceNameAndLocalConfig: Decoder[(LdapService.Name, Option[PositiveFiniteDuration])] = {
    Decoder
      .instance { c =>
        for {
          name <- c.downField("name").as[LdapService.Name]
          ttl <- c.downFields("cache_ttl_in_sec", "cache_ttl").as[Option[PositiveFiniteDuration]]
        } yield (name, ttl)
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .decoder
  }
}

// ------ ldap_authorization
class LdapAuthorizationRuleDecoder(ldapDefinitions: Definitions[LdapService],
                                   impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                                   mocksProvider: MocksProvider,
                                   globalSettings: GlobalSettings)
  extends RuleBaseDecoderWithoutAssociatedFields[LdapAuthorizationRule] {

  override protected def decoder: Decoder[RuleDefinition[LdapAuthorizationRule]] = {
    settingsDecoder(ldapDefinitions)
      .map(settings => RuleDefinition.create(
        new LdapAuthorizationRule(
          settings,
          globalSettings.userIdCaseSensitivity,
          impersonatorsDef.toImpersonation(mocksProvider)
        )
      ))
  }

  private def settingsDecoder(ldapDefinitions: Definitions[LdapService]): Decoder[LdapAuthorizationRule.Settings] =
    Decoder
      .instance { c =>
        for {
          name <- c.downField("name").as[LdapService.Name]
          groupsAnd <- c.downFields("groups_and", "groups_all_of").as[Option[GroupsLogic.And]]
          groupsOr <- c.downFields("groups_or", "groups", "groups_any_of").as[Option[GroupsLogic.Or]]
          groupsNotAllOf <- c.downFields("groups_not_all_of").as[Option[GroupsLogic.NotAllOf]]
          groupsNotAnyOf <- c.downFields("groups_not_any_of").as[Option[GroupsLogic.NotAnyOf]]
          ttl <- c.downFields("cache_ttl_in_sec", "cache_ttl").as[Option[PositiveFiniteDuration]]
        } yield (name, ttl, groupsOr, groupsAnd, groupsNotAllOf, groupsNotAnyOf)
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .emapE {
        case (_, _, None, None, None, None) =>
          Left(RulesLevelCreationError(Message(errorMsgNoGroupsList(LdapAuthorizationRule.Name))))
        case (name, ttl, Some(groupsOr), None, None, None) =>
          createLdapAuthorizationRule(name, ttl, groupsOr, ldapDefinitions)
        case (name, ttl, None, Some(groupsAnd), None, None) =>
          createLdapAuthorizationRule(name, ttl, groupsAnd, ldapDefinitions)
        case (name, ttl, None, None, Some(groupsNotAllOf), None) =>
          createLdapAuthorizationRule(name, ttl, groupsNotAllOf, ldapDefinitions)
        case (name, ttl, None, None, None, Some(groupsNotAnyOf)) =>
          createLdapAuthorizationRule(name, ttl, groupsNotAnyOf, ldapDefinitions)
        case (name, ttl, Some(groupsOr), None, Some(groupsNotAllOf), None) =>
          val logic = GroupsLogic.CombinedGroupsLogic(groupsOr, groupsNotAllOf)
          createLdapAuthorizationRule(name, ttl, logic, ldapDefinitions)
        case (name, ttl, None, Some(groupsAnd), Some(groupsNotAllOf), None) =>
          val logic = GroupsLogic.CombinedGroupsLogic(groupsAnd, groupsNotAllOf)
          createLdapAuthorizationRule(name, ttl, logic, ldapDefinitions)
        case (name, ttl, Some(groupsOr), None, None, Some(groupsNotAnyOf)) =>
          val logic = GroupsLogic.CombinedGroupsLogic(groupsOr, groupsNotAnyOf)
          createLdapAuthorizationRule(name, ttl, logic, ldapDefinitions)
        case (name, ttl, None, Some(groupsAnd), None, Some(groupsNotAnyOf)) =>
          val logic = GroupsLogic.CombinedGroupsLogic(groupsAnd, groupsNotAnyOf)
          createLdapAuthorizationRule(name, ttl, logic, ldapDefinitions)
        case (_, _, _, _, _, _) =>
          Left(RulesLevelCreationError(Message(errorMsgOnlyOneGroupsList(LdapAuthorizationRule.Name))))
      }
      .decoder

  private def createLdapAuthorizationRule(name: LdapService.Name,
                                          ttl: Option[PositiveFiniteDuration],
                                          groupsLogic: GroupsLogic,
                                          ldapDefinitions: Definitions[LdapService]): Either[CoreCreationError, LdapAuthorizationRule.Settings] = {
    findLdapService[LdapAuthorizationRule](LdapServiceType.Authorization, name, ldapDefinitions.items)
      .map(CacheableLdapAuthorizationService.createWithCacheableLdapUsersService(_, ttl))
      .map(service => LoggableLdapAuthorizationService.create(service))
      .flatMap(ldapAuthorizationRuleSettings(_, groupsLogic))
  }
}

// ------ ldap_auth
class LdapAuthRuleDecoder(ldapDefinitions: Definitions[LdapService],
                          impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                          mocksProvider: MocksProvider,
                          globalSettings: GlobalSettings)
  extends RuleBaseDecoderWithoutAssociatedFields[LdapAuthRule] {

  override protected def decoder: Decoder[RuleDefinition[LdapAuthRule]] = {
    instance(ldapDefinitions, globalSettings.userIdCaseSensitivity, impersonatorsDef, mocksProvider)
      .map(RuleDefinition.create(_))
  }

  private def instance(ldapDefinitions: Definitions[LdapService],
                       userIdCaseSensitivity: CaseSensitivity,
                       impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                       mocksProvider: MocksProvider): Decoder[LdapAuthRule] =
    Decoder
      .instance { c =>
        for {
          name <- c.downField("name").as[LdapService.Name]
          groupsAnd <- c.downFields("groups_and", "groups_all_of").as[Option[GroupsLogic.And]]
          groupsOr <- c.downFields("groups_or", "groups", "groups_any_of").as[Option[GroupsLogic.Or]]
          groupsNotAllOf <- c.downFields("groups_not_all_of").as[Option[GroupsLogic.NotAllOf]]
          groupsNotAnyOf <- c.downFields("groups_not_any_of").as[Option[GroupsLogic.NotAnyOf]]
          ttl <- c.downFields("cache_ttl_in_sec", "cache_ttl").as[Option[PositiveFiniteDuration]]
        } yield (name, ttl, groupsOr, groupsAnd, groupsNotAllOf, groupsNotAnyOf)
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .emapE {
        case (_, _, None, None, None, None) =>
          Left(RulesLevelCreationError(Message(errorMsgNoGroupsList(LdapAuthRule.Name))))
        case (name, ttl, Some(groupsOr), None, None, None) =>
          createLdapAuthRule(name, ttl, groupsOr, ldapDefinitions, impersonatorsDef, mocksProvider, userIdCaseSensitivity)
        case (name, ttl, None, Some(groupsAnd), None, None) =>
          createLdapAuthRule(name, ttl, groupsAnd, ldapDefinitions, impersonatorsDef, mocksProvider, userIdCaseSensitivity)
        case (name, ttl, None, None, Some(groupsNotAllOf), None) =>
          createLdapAuthRule(name, ttl, groupsNotAllOf, ldapDefinitions, impersonatorsDef, mocksProvider, userIdCaseSensitivity)
        case (name, ttl, None, None, None, Some(groupsNotAnyOf)) =>
          createLdapAuthRule(name, ttl, groupsNotAnyOf, ldapDefinitions, impersonatorsDef, mocksProvider, userIdCaseSensitivity)
        case (name, ttl, Some(groupsOr), None, Some(groupsNotAllOf), None) =>
          val logic = GroupsLogic.CombinedGroupsLogic(groupsOr, groupsNotAllOf)
          createLdapAuthRule(name, ttl, logic, ldapDefinitions, impersonatorsDef, mocksProvider, userIdCaseSensitivity)
        case (name, ttl, None, Some(groupsAnd), Some(groupsNotAllOf), None) =>
          val logic = GroupsLogic.CombinedGroupsLogic(groupsAnd, groupsNotAllOf)
          createLdapAuthRule(name, ttl, logic, ldapDefinitions, impersonatorsDef, mocksProvider, userIdCaseSensitivity)
        case (name, ttl, Some(groupsOr), None, None, Some(groupsNotAnyOf)) =>
          val logic = GroupsLogic.CombinedGroupsLogic(groupsOr, groupsNotAnyOf)
          createLdapAuthRule(name, ttl, logic, ldapDefinitions, impersonatorsDef, mocksProvider, userIdCaseSensitivity)
        case (name, ttl, None, Some(groupsAnd), None, Some(groupsNotAnyOf)) =>
          val logic = GroupsLogic.CombinedGroupsLogic(groupsAnd, groupsNotAnyOf)
          createLdapAuthRule(name, ttl, logic, ldapDefinitions, impersonatorsDef, mocksProvider, userIdCaseSensitivity)
        case (_, _, _, _, _, _) =>
          Left(RulesLevelCreationError(Message(errorMsgOnlyOneGroupsList(LdapAuthorizationRule.Name))))
      }
      .decoder

  private def createLdapAuthRule(name: LdapService.Name,
                                 ttl: Option[PositiveFiniteDuration],
                                 groupsLogic: GroupsLogic, ldapDefinitions: Definitions[LdapService],
                                 impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                                 mocksProvider: MocksProvider,
                                 userIdCaseSensitivity: CaseSensitivity) = {
    for
      ldapService <- findLdapService[LdapAuthRule](LdapServiceType.Composed, name, ldapDefinitions.items)
      ldapAuthorizationService = LoggableLdapAuthorizationService.create(
        CacheableLdapAuthorizationService.create(ldapService.ldapAuthorizationService, ttl)
      )
      settings <- ldapAuthorizationRuleSettings(ldapAuthorizationService, groupsLogic)
    yield new LdapAuthRule(
      new LdapAuthenticationRule(
        LdapAuthenticationRule.Settings(
          new LoggableLdapAuthenticationServiceDecorator(
            CacheableLdapAuthenticationServiceDecorator.create(ldapService.ldapAuthenticationService, ttl)
          )
        ),
        userIdCaseSensitivity,
        impersonatorsDef.toImpersonation(mocksProvider)
      ),
      new LdapAuthorizationRule(
        settings,
        userIdCaseSensitivity,
        impersonatorsDef.toImpersonation(mocksProvider)
      )
    )
  }
}

private object LdapRulesDecodersHelper {

  private[rules] def errorMsgNoGroupsList[R <: Rule](ruleName: RuleName[R]) = {
    s"${ruleName.show} rule requires to define 'groups_or'/'groups' or 'groups_and' arrays"
  }

  private[rules] def errorMsgOnlyOneGroupsList[R <: Rule](ruleName: RuleName[R]) = {
    s"${ruleName.show} rule requires to define 'groups_or'/'groups' or 'groups_and' arrays (but not both)"
  }

  private[rules] def negativeGroupsRuleNotAllowedForLdapWithGroupsFiltering = {
    s"It is not allowed to use groups_not_any_of and groups_not_all_of rule, when LDAP server-side groups filtering is enabled. Consider using a combined rule, which merges the groups_not_any_of/groups_not_all_of with groups_or in a single rule."
  }

  private[rules] def findLdapService[R <: Rule : RuleName](toFind: LdapServiceType,
                                                           searchedServiceName: LdapService.Name,
                                                           ldapServices: List[LdapService]): Either[CoreCreationError, toFind.LDAP_SERVICE] = {
    ldapServices.find(_.id === searchedServiceName) match {
      case Some(service) =>
        (service, toFind) match {
          case (service: LdapAuthenticationService, LdapServiceType.Authentication) =>
            Right(service.asInstanceOf[toFind.LDAP_SERVICE])
          case (service: LdapAuthorizationService, LdapServiceType.Authorization) =>
            Right(service.asInstanceOf[toFind.LDAP_SERVICE])
          case (service: ComposedLdapAuthService, LdapServiceType.Composed) =>
            Right(service.asInstanceOf[toFind.LDAP_SERVICE])
          case (service: ComposedLdapAuthService, LdapServiceType.Authentication) =>
            Right(service.ldapAuthenticationService.asInstanceOf[toFind.LDAP_SERVICE])
          case (service: ComposedLdapAuthService, LdapServiceType.Authorization) =>
            Right(service.ldapAuthorizationService.asInstanceOf[toFind.LDAP_SERVICE])
          case (_, _) =>
            Left(RulesLevelCreationError(Message(s"Service: ${searchedServiceName.show} cannot be used in '${RuleName[R].show}' rule")))
        }
      case None =>
        Left(RulesLevelCreationError(Message(s"Cannot find LDAP service with name: ${searchedServiceName.show}")))
    }
  }

  private[rules] def ldapAuthorizationRuleSettings(ldapService: LdapAuthorizationService,
                                                   groupsLogic: GroupsLogic): Either[RulesLevelCreationError, LdapAuthorizationRule.Settings] =
    ldapService match
      case ldap: LdapAuthorizationService.WithoutGroupsFiltering =>
        Right(LdapAuthorizationRule.Settings(ldap, groupsLogic))
      case ldap: LdapAuthorizationService.WithGroupsFiltering =>
        groupsLogic match
          case groupsLogic: GroupsLogic.CombinedGroupsLogic =>
            Right(LdapAuthorizationRule.Settings(ldap, groupsLogic))
          case groupsLogic: GroupsLogic.PositiveGroupsLogic =>
            Right(LdapAuthorizationRule.Settings(ldap, groupsLogic))
          case groupsLogic: GroupsLogic.NegativeGroupsLogic =>
            Left(RulesLevelCreationError(Message(negativeGroupsRuleNotAllowedForLdapWithGroupsFiltering)))

  private[auth] sealed trait LdapServiceType {
    type LDAP_SERVICE
  }

  private[auth] object LdapServiceType {
    case object Authentication extends LdapServiceType {
      override type LDAP_SERVICE = LdapAuthenticationService
    }

    case object Authorization extends LdapServiceType {
      override type LDAP_SERVICE = LdapAuthorizationService
    }

    case object Composed extends LdapServiceType {
      override type LDAP_SERVICE = ComposedLdapAuthService
    }
  }
}