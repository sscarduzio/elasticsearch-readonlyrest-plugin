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
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap._
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.{LdapAuthRule, LdapAuthenticationRule, LdapAuthorizationRule, Rule}
import tech.beshu.ror.accesscontrol.domain.Group
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.LdapServicesDecoder.nameDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.{Definitions, LdapServicesDecoder}
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import EligibleUsers.Instances._

class LdapAuthenticationRuleDecoder(ldapDefinitions: Definitions[LdapService],
                                    implicit val caseMappingEquality: UserIdCaseMappingEquality)
  extends AuthenticationRuleDecoder[LdapAuthenticationRule]
    with RuleDecoderWithoutAssociatedFields[LdapAuthenticationRule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[LdapAuthenticationRule]] = {
    LdapAuthenticationRuleDecoder.simpleLdapAuthenticationNameAndLocalConfig
      .orElse(LdapAuthenticationRuleDecoder.complexLdapAuthenticationServiceNameAndLocalConfig)
      .toSyncDecoder
      .emapE {
        case (name, Some(ttl)) =>
          LdapRulesDecodersHelper
            .findLdapService[LdapAuthenticationService](ldapDefinitions.items, name, LdapAuthenticationRule.name)
            .map(new CacheableLdapAuthenticationServiceDecorator(_, ttl))
        case (name, None) =>
          LdapRulesDecodersHelper
            .findLdapService[LdapAuthenticationService](ldapDefinitions.items, name, LdapAuthenticationRule.name)
      }
      .map(new LoggableLdapAuthenticationServiceDecorator(_))
      .map(service => RuleWithVariableUsageDefinition.create(new LdapAuthenticationRule(LdapAuthenticationRule.Settings(service), caseMappingEquality)))
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

class LdapAuthorizationRuleDecoder(ldapDefinitions: Definitions[LdapService])
  extends AuthorizationRuleDecoder[LdapAuthorizationRule]
    with RuleDecoderWithoutAssociatedFields[LdapAuthorizationRule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[LdapAuthorizationRule]] = {
    LdapAuthorizationRuleDecoder
      .settingsDecoder(ldapDefinitions)
      .map(settings => RuleWithVariableUsageDefinition.create(new LdapAuthorizationRule(settings)))
  }
}

object LdapAuthorizationRuleDecoder {

  private def settingsDecoder(ldapDefinitions: Definitions[LdapService]): Decoder[LdapAuthorizationRule.Settings] =
    Decoder
      .instance { c =>
        for {
          name <- c.downField("name").as[LdapService.Name]
          groups <- c.downField("groups").as[UniqueNonEmptyList[Group]]
          ttl <- c.downFields("cache_ttl_in_sec", "cache_ttl").as[Option[FiniteDuration Refined Positive]]
        } yield (name, ttl, groups)
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .emapE {
        case (name, Some(ttl), groups) =>
          LdapRulesDecodersHelper
            .findLdapService[LdapAuthorizationService](ldapDefinitions.items, name, LdapAuthorizationRule.name)
            .map(new CacheableLdapAuthorizationServiceDecorator(_, ttl))
            .map(new LoggableLdapAuthorizationServiceDecorator(_))
            .map(LdapAuthorizationRule.Settings(_, groups, groups))
        case (name, None, groups) =>
          LdapRulesDecodersHelper
            .findLdapService[LdapAuthorizationService](ldapDefinitions.items, name, LdapAuthorizationRule.name)
            .map(new LoggableLdapAuthorizationServiceDecorator(_))
            .map(LdapAuthorizationRule.Settings(_, groups, groups))
      }
      .decoder
}

class LdapAuthRuleDecoder(ldapDefinitions: Definitions[LdapService],
                          caseMappingEquality: UserIdCaseMappingEquality)
  extends AuthRuleDecoder[LdapAuthRule]
    with RuleDecoderWithoutAssociatedFields[LdapAuthRule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[LdapAuthRule]] = {
    LdapAuthRuleDecoder.instance(ldapDefinitions, caseMappingEquality)
      .map(RuleWithVariableUsageDefinition.create(_))
  }
}

object LdapAuthRuleDecoder {

  private def instance(ldapDefinitions: Definitions[LdapService],
                      caseMappingEquality: UserIdCaseMappingEquality): Decoder[LdapAuthRule] =
    Decoder
      .instance { c =>
        for {
          name <- c.downField("name").as[LdapService.Name]
          groups <- c.downField("groups").as[UniqueNonEmptyList[Group]]
          ttl <- c.downFields("cache_ttl_in_sec", "cache_ttl").as[Option[FiniteDuration Refined Positive]]
        } yield (name, ttl, groups)
      }
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .emapE {
        case (name, Some(ttl), groups) =>
          LdapRulesDecodersHelper
            .findLdapService[LdapAuthService](ldapDefinitions.items, name, LdapAuthRule.name)
            .map(new CacheableLdapServiceDecorator(_, ttl))
            .map(new LoggableLdapServiceDecorator(_))
            .map(createLdapAuthRule(_, groups, caseMappingEquality))
        case (name, None, groups) =>
          LdapRulesDecodersHelper
            .findLdapService[LdapAuthService](ldapDefinitions.items, name, LdapAuthRule.name)
            .map(new LoggableLdapServiceDecorator(_))
            .map(createLdapAuthRule(_, groups, caseMappingEquality))
      }
      .decoder

  private def createLdapAuthRule(ldapService: LdapAuthService,
                                 groups: UniqueNonEmptyList[Group],
                                 caseMappingEquality: UserIdCaseMappingEquality) = {
    new LdapAuthRule(
      new LdapAuthenticationRule(LdapAuthenticationRule.Settings(ldapService), caseMappingEquality),
      new LdapAuthorizationRule(LdapAuthorizationRule.Settings(ldapService, groups, groups)),
      caseMappingEquality
    )
  }
}

private object LdapRulesDecodersHelper {

  def findLdapService[T <: LdapService : ClassTag](ldapServices: List[LdapService],
                                                   searchedServiceName: LdapService.Name,
                                                   ruleName: Rule.Name): Either[AclCreationError, T] = {
    ldapServices
      .find(_.id === searchedServiceName) match {
      case Some(service: T) => Right(service)
      case Some(_) => Left(RulesLevelCreationError(Message(s"Service: ${searchedServiceName.show} cannot be used in '${ruleName.show}' rule")))
      case None => Left(RulesLevelCreationError(Message(s"Cannot find LDAP service with name: ${searchedServiceName.show}")))
    }
  }
}