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
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.{LdapAuthRule, LdapAuthenticationRule, LdapAuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.Group
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.LdapServicesDecoder.nameDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.{Definitions, LdapServicesDecoder}
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

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
            .findLdapService[LdapAuthorizationService, LdapAuthorizationRule](ldapDefinitions.items, name)
            .map(new CacheableLdapAuthorizationServiceDecorator(_, ttl))
            .map(new LoggableLdapAuthorizationServiceDecorator(_))
            .map(LdapAuthorizationRule.Settings(_, groups, groups))
        case (name, None, groups) =>
          LdapRulesDecodersHelper
            .findLdapService[LdapAuthorizationService, LdapAuthorizationRule](ldapDefinitions.items, name)
            .map(new LoggableLdapAuthorizationServiceDecorator(_))
            .map(LdapAuthorizationRule.Settings(_, groups, groups))
      }
      .decoder
}

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

  private def instance(ldapDefinitions: Definitions[LdapService],
                       impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                       mocksProvider: MocksProvider,
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
            .findLdapService[LdapAuthService, LdapAuthRule](ldapDefinitions.items, name)
            .map(new CacheableLdapServiceDecorator(_, ttl))
            .map(new LoggableLdapServiceDecorator(_))
            .map(createLdapAuthRule(_, impersonatorsDef, mocksProvider, groups, caseMappingEquality))
        case (name, None, groups) =>
          LdapRulesDecodersHelper
            .findLdapService[LdapAuthService, LdapAuthRule](ldapDefinitions.items, name)
            .map(new LoggableLdapServiceDecorator(_))
            .map(createLdapAuthRule(_, impersonatorsDef, mocksProvider, groups, caseMappingEquality))
      }
      .decoder

  private def createLdapAuthRule(ldapService: LdapAuthService,
                                 impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                                 mocksProvider: MocksProvider,
                                 groups: UniqueNonEmptyList[Group],
                                 caseMappingEquality: UserIdCaseMappingEquality) = {
    val impersonation = impersonatorsDef.toImpersonation(mocksProvider)
    new LdapAuthRule(
      new LdapAuthenticationRule(
        LdapAuthenticationRule.Settings(ldapService),
        impersonation,
        caseMappingEquality
      ),
      new LdapAuthorizationRule(
        LdapAuthorizationRule.Settings(ldapService, groups, groups),
        impersonation,
        caseMappingEquality
      )
    )
  }
}

private object LdapRulesDecodersHelper {

  def findLdapService[T <: LdapService : ClassTag, R <: Rule : RuleName](ldapServices: List[LdapService],
                                                                         searchedServiceName: LdapService.Name): Either[CoreCreationError, T] = {
    ldapServices
      .find(_.id === searchedServiceName) match {
      case Some(service: T) => Right(service)
      case Some(_) => Left(RulesLevelCreationError(Message(s"Service: ${searchedServiceName.show} cannot be used in '${RuleName[R].show}' rule")))
      case None => Left(RulesLevelCreationError(Message(s"Cannot find LDAP service with name: ${searchedServiceName.show}")))
    }
  }
}