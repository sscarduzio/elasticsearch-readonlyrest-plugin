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

import cats.implicits._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap._
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{LdapAuthRule, LdapAuthenticationRule, LdapAuthorizationRule}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, GroupsLogic}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.LdapServicesDecoder.nameDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.{Definitions, LdapServicesDecoder}
import tech.beshu.ror.accesscontrol.factory.decoders.rules.OptionalImpersonatorDefinitionOps
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.LdapRulesDecodersHelper.{errorMsgNoGroupsList, errorMsgOnlyOneGroupsList, findLdapService}
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps._

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

// ------ ldap_authentication
class LdapAuthenticationRuleDecoder(ldapDefinitions: Definitions[LdapService],
                                    impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                                    mocksProvider: MocksProvider,
                                    globalSettings: GlobalSettings)
  extends RuleBaseDecoderWithoutAssociatedFields[LdapAuthenticationRule] {

  override protected def decoder: Decoder[RuleDefinition[LdapAuthenticationRule]] = {
    simpleLdapAuthenticationNameAndLocalConfig
      .orElse(complexLdapAuthenticationServiceNameAndLocalConfig)
      .toSyncDecoder
      .emapE {
        case (name, Some(ttl)) =>
          LdapRulesDecodersHelper
            .findLdapService[LdapAuthenticationService, LdapAuthenticationRule](ldapDefinitions.items, name)
            .map(new CacheableLdapAuthenticationServiceDecorator(_, ttl))
        case (name, None) =>
          LdapRulesDecodersHelper
            .findLdapService[LdapAuthenticationService, LdapAuthenticationRule](ldapDefinitions.items, name)
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

  private def simpleLdapAuthenticationNameAndLocalConfig: Decoder[(LdapService.Name, Option[FiniteDuration Refined Positive])] =
    LdapServicesDecoder.nameDecoder
      .map((_, None))

  private def complexLdapAuthenticationServiceNameAndLocalConfig: Decoder[(LdapService.Name, Option[FiniteDuration Refined Positive])] = {
    Decoder
      .instance { c =>
        for {
          name <- c.downField("name").as[LdapService.Name]
          ttl <- c.downFields("cache_ttl_in_sec", "cache_ttl").as[Option[FiniteDuration Refined Positive]]
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
          groupsAnd <- c.downField("groups_and").as[Option[GroupsLogic.And]]
          groupsOr <- c.downFields("groups_or", "groups").as[Option[GroupsLogic.Or]]
          ttl <- c.downFields("cache_ttl_in_sec", "cache_ttl").as[Option[FiniteDuration Refined Positive]]
        } yield (name, ttl, groupsOr, groupsAnd)
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .emapE {
        case (_, _, None, None) =>
          Left(RulesLevelCreationError(Message(errorMsgNoGroupsList(LdapAuthorizationRule.Name))))
        case (_, _, Some(_), Some(_)) =>
          Left(RulesLevelCreationError(Message(errorMsgOnlyOneGroupsList(LdapAuthorizationRule.Name))))
        case (name, ttl, Some(groupsOr), None) =>
          createLdapAuthorizationRule(name, ttl, groupsOr, ldapDefinitions)
        case (name, ttl, None, Some(groupsAnd)) =>
          createLdapAuthorizationRule(name, ttl, groupsAnd, ldapDefinitions)
      }
      .decoder

  private def createLdapAuthorizationRule(name: LdapService.Name,
                                          ttl: Option[Refined[FiniteDuration, Positive]],
                                          groupsLogic: GroupsLogic,
                                          ldapDefinitions: Definitions[LdapService]): Either[CoreCreationError, LdapAuthorizationRule.Settings] = {
    findLdapService[LdapAuthorizationServiceWithGroupsFiltering, LdapAuthorizationRule](ldapDefinitions.items, name)
      .map(svc => {
        ttl match {
          case Some(ttlValue) => new CacheableLdapAuthorizationServiceWithGroupsFilteringDecorator(svc, ttlValue)
          case _ => svc
        }
      })
      .map(service => new LoggableLdapAuthorizationServiceWithGroupsFilteringDecorator(service))
      .map(LdapAuthorizationRule.Settings(_, groupsLogic))
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
          groupsAnd <- c.downField("groups_and").as[Option[GroupsLogic.And]]
          groupsOr <- c.downFields("groups_or", "groups").as[Option[GroupsLogic.Or]]
          ttl <- c.downFields("cache_ttl_in_sec", "cache_ttl").as[Option[FiniteDuration Refined Positive]]
        } yield (name, ttl, groupsOr, groupsAnd)
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .emapE {
        case (_, _, None, None) =>
          Left(RulesLevelCreationError(Message(errorMsgNoGroupsList(LdapAuthRule.Name))))
        case (_, _, Some(_), Some(_)) =>
          Left(RulesLevelCreationError(Message(errorMsgOnlyOneGroupsList(LdapAuthRule.Name))))
        case (name, ttl, Some(groupsOr), None) =>
          createLdapAuthRule(name, ttl, groupsOr, ldapDefinitions, impersonatorsDef, mocksProvider, userIdCaseSensitivity)
        case (name, ttl, None, Some(groupsAnd)) =>
          createLdapAuthRule(name, ttl, groupsAnd, ldapDefinitions, impersonatorsDef, mocksProvider, userIdCaseSensitivity)
      }
      .decoder

  private def createLdapAuthRule(name: LdapService.Name,
                                 ttl: Option[Refined[FiniteDuration, Positive]],
                                 groupsLogic: GroupsLogic, ldapDefinitions: Definitions[LdapService],
                                 impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                                 mocksProvider: MocksProvider,
                                 userIdCaseSensitivity: CaseSensitivity) = {
    findLdapService[LdapAuthService, LdapAuthRule](ldapDefinitions.items, name)
      .map(svc => {
        ttl match {
          case Some(ttlValue) => new CacheableLdapServiceDecorator(svc, ttlValue)
          case _ => svc
        }
      })
      .map(new LoggableLdapServiceDecorator(_))
      .map(ldapService => {
        new LdapAuthRule(
          new LdapAuthenticationRule(
            LdapAuthenticationRule.Settings(ldapService),
            userIdCaseSensitivity,
            impersonatorsDef.toImpersonation(mocksProvider)
          ),
          new LdapAuthorizationRule(
            LdapAuthorizationRule.Settings(ldapService, groupsLogic),
            userIdCaseSensitivity,
            impersonatorsDef.toImpersonation(mocksProvider)
          )
        )
      })
  }
}

private object LdapRulesDecodersHelper {

  private[rules] def errorMsgNoGroupsList[R <: Rule](ruleName: RuleName[R]) = {
    s"${ruleName.show} rule requires to define 'groups_or'/'groups' or 'groups_and' arrays"
  }

  private[rules] def errorMsgOnlyOneGroupsList[R <: Rule](ruleName: RuleName[R]) = {
    s"${ruleName.show} rule requires to define 'groups_or'/'groups' or 'groups_and' arrays (but not both)"
  }

  private[rules] def findLdapService[T <: LdapService : ClassTag, R <: Rule : RuleName](ldapServices: List[LdapService],
                                                                                        searchedServiceName: LdapService.Name): Either[CoreCreationError, T] = {
    ldapServices
      .find(_.id === searchedServiceName) match {
      case Some(service: T) => Right(service)
      case Some(_) => Left(RulesLevelCreationError(Message(s"Service: ${searchedServiceName.show} cannot be used in '${RuleName[R].show}' rule")))
      case None => Left(RulesLevelCreationError(Message(s"Cannot find LDAP service with name: ${searchedServiceName.show}")))
    }
  }
}