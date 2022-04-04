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
package tech.beshu.ror.accesscontrol.factory.decoders.rules

import cats.implicits._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap._
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.LdapAuthorizationRule.GroupsLogic
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.{LdapAuthRule, LdapAuthenticationRule, LdapAuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.Group
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.LdapServicesDecoder.nameDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.{Definitions, LdapServicesDecoder}
import tech.beshu.ror.accesscontrol.factory.decoders.rules.LdapRulesDecodersHelper.{findLdapService, errorMsgNoGroupsList, errorMsgOnlyOneGroupsList}
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

// ------ ldap_authentication
class LdapAuthenticationRuleDecoder(ldapDefinitions: Definitions[LdapService],
                                    impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                                    mocksProvider: MocksProvider,
                                    caseMappingEquality: UserIdCaseMappingEquality)
  extends RuleBaseDecoderWithoutAssociatedFields[LdapAuthenticationRule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[LdapAuthenticationRule]] = {
    LdapAuthenticationRuleDecoder.simpleLdapAuthenticationNameAndLocalConfig
      .orElse(LdapAuthenticationRuleDecoder.complexLdapAuthenticationServiceNameAndLocalConfig)
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
      .map(service => RuleWithVariableUsageDefinition.create(
        new LdapAuthenticationRule(
          LdapAuthenticationRule.Settings(service),
          impersonatorsDef.toImpersonation(mocksProvider),
          caseMappingEquality
        )
      ))
      .decoder
  }
}

object LdapAuthenticationRuleDecoder {

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
                                   caseMappingEquality: UserIdCaseMappingEquality)
  extends RuleBaseDecoderWithoutAssociatedFields[LdapAuthorizationRule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[LdapAuthorizationRule]] = {
    LdapAuthorizationRuleDecoder
      .settingsDecoder(ldapDefinitions)
      .map(settings => RuleWithVariableUsageDefinition.create(
        new LdapAuthorizationRule(settings, impersonatorsDef.toImpersonation(mocksProvider), caseMappingEquality)
      ))
  }
}

object LdapAuthorizationRuleDecoder {
  def createLdapAuthorizationDecoder(name: LdapService.Name,
                                     ttl: Option[Refined[FiniteDuration, Positive]],
                                     groupsLogic: GroupsLogic,
                                     ldapDefinitions: Definitions[LdapService]
                                    ) = {

    findLdapService[LdapAuthorizationService, LdapAuthorizationRule](ldapDefinitions.items, name)
      .map(svc => {
        ttl match {
          case Some(ttlValue) => new CacheableLdapAuthorizationServiceDecorator(svc, ttlValue)
          case _ => svc
        }
      })
      .map(x => new LoggableLdapAuthorizationServiceDecorator(x))
      .map(LdapAuthorizationRule.Settings(_, groupsLogic, groupsLogic.groups))
  }

  private def settingsDecoder(ldapDefinitions: Definitions[LdapService]): Decoder[LdapAuthorizationRule.Settings] =
    Decoder
      .instance { c =>
        for {
          name <- c.downField("name").as[LdapService.Name]
          groupsAnd <- c.downField("groups_and").as[Option[UniqueNonEmptyList[Group]]].map(_.map(GroupsLogic.And))
          groupsOr <- c.downField("groups").as[Option[UniqueNonEmptyList[Group]]].map(_.map(GroupsLogic.Or))
          ttl <- c.downFields("cache_ttl_in_sec", "cache_ttl").as[Option[FiniteDuration Refined Positive]]
        } yield (name, ttl, groupsOr, groupsAnd)
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .emapE {
        case (name, _, None, None) => Left(RulesLevelCreationError(Message(errorMsgNoGroupsList(name.value.value))))
        case (name, _, Some(_), Some(_)) => Left(RulesLevelCreationError(Message(errorMsgOnlyOneGroupsList(name.value.value))))
        case (name, ttl, Some(groupsOr), None) => createLdapAuthorizationDecoder(name, ttl, groupsOr, ldapDefinitions)
        case (name, ttl, None, Some(groupsAnd)) => createLdapAuthorizationDecoder(name, ttl, groupsAnd, ldapDefinitions)
      }.decoder
}

// ------ ldap_auth
class LdapAuthRuleDecoder(ldapDefinitions: Definitions[LdapService],
                          impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                          mocksProvider: MocksProvider,
                          caseMappingEquality: UserIdCaseMappingEquality)
  extends RuleBaseDecoderWithoutAssociatedFields[LdapAuthRule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[LdapAuthRule]] = {
    LdapAuthRuleDecoder.instance(ldapDefinitions, impersonatorsDef, mocksProvider, caseMappingEquality)
      .map(RuleWithVariableUsageDefinition.create(_))
  }
}

object LdapAuthRuleDecoder {

  private def createLdapAuthDecoder(name: LdapService.Name,
                                    ttl: Option[Refined[FiniteDuration, Positive]],
                                    groupsLogic: GroupsLogic, ldapDefinitions: Definitions[LdapService],
                                    impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                                    mocksProvider: MocksProvider,
                                    caseMappingEquality: UserIdCaseMappingEquality
                                   ) = {

    findLdapService[LdapAuthService, LdapAuthRule](ldapDefinitions.items, name)
      .map( svc => {
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
            impersonatorsDef.toImpersonation(mocksProvider),
            caseMappingEquality
          ),
          new LdapAuthorizationRule(
            LdapAuthorizationRule.Settings(ldapService, groupsLogic, groupsLogic.groups),
            impersonatorsDef.toImpersonation(mocksProvider),
            caseMappingEquality
          )
        )
      })
  }

  private def instance(ldapDefinitions: Definitions[LdapService],
                       impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                       mocksProvider: MocksProvider,
                       caseMappingEquality: UserIdCaseMappingEquality): Decoder[LdapAuthRule] =
    Decoder
      .instance { c =>
        for {
          name <- c.downField("name").as[LdapService.Name]
          groupsAnd <- c.downField("groups_and").as[Option[UniqueNonEmptyList[Group]]].map(_.map(GroupsLogic.And))
          groupsOr <- c.downField("groups").as[Option[UniqueNonEmptyList[Group]]].map(_.map(GroupsLogic.Or))
          ttl <- c.downFields("cache_ttl_in_sec", "cache_ttl").as[Option[FiniteDuration Refined Positive]]
        } yield (name, ttl, groupsOr, groupsAnd)
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .emapE {
        case (name, _, None, None) => Left(RulesLevelCreationError(Message(errorMsgNoGroupsList(name.value.value))))
        case (name, _, Some(_), Some(_)) => Left(RulesLevelCreationError(Message(errorMsgOnlyOneGroupsList(name.value.value))))
        case (name, ttl, Some(groupsOr), None) =>
          createLdapAuthDecoder(name, ttl, groupsOr, ldapDefinitions, impersonatorsDef, mocksProvider, caseMappingEquality)
        case (name, ttl, None, Some(groupsAnd)) =>
          createLdapAuthDecoder(name, ttl, groupsAnd, ldapDefinitions, impersonatorsDef, mocksProvider, caseMappingEquality)
      }
      .decoder
}

private object LdapRulesDecodersHelper {

  def errorMsgNoGroupsList(name: String) = s"Please specify one between 'groups' or 'groups_and' for LDAP authorization rule '${name}'"

  def errorMsgOnlyOneGroupsList(name: String) = s"Please specify either 'groups' or 'groups_and' for LDAP authorization rule '${name}'"

  def findLdapService[T <: LdapService : ClassTag, R <: Rule : RuleName](ldapServices: List[LdapService],
                                                                         searchedServiceName: LdapService.Name): Either[AclCreationError, T] = {
    ldapServices
      .find(_.id === searchedServiceName) match {
      case Some(service: T) => Right(service)
      case Some(_) => Left(RulesLevelCreationError(Message(s"Service: ${searchedServiceName.show} cannot be used in '${RuleName[R].show}' rule")))
      case None => Left(RulesLevelCreationError(Message(s"Cannot find LDAP service with name: ${searchedServiceName.show}")))
    }
  }
}