package tech.beshu.ror.acl.factory.decoders.rules

import cats.data.NonEmptySet
import cats.implicits._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.definitions.ldap.{LdapAuthService, _}
import tech.beshu.ror.acl.blocks.rules.{LdapAuthRule, LdapAuthenticationRule, LdapAuthorizationRule, Rule}
import tech.beshu.ror.acl.domain.Group
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.common._
import tech.beshu.ror.acl.factory.decoders.definitions.LdapServicesDecoder.nameDecoder
import tech.beshu.ror.acl.factory.decoders.definitions.{Definitions, LdapServicesDecoder}
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps._

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

class LdapAuthenticationRuleDecoder(ldapDefinitions: Definitions[LdapService])
  extends RuleDecoderWithoutAssociatedFields[LdapAuthenticationRule](
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
      .map(service => new LdapAuthenticationRule(LdapAuthenticationRule.Settings(service)))
      .decoder
  )

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
  extends RuleDecoderWithoutAssociatedFields[LdapAuthorizationRule](
    LdapAuthorizationRuleDecoder
      .settingsDecoder(ldapDefinitions)
      .map(new LdapAuthorizationRule(_))
  )

object LdapAuthorizationRuleDecoder {

  private def settingsDecoder(ldapDefinitions: Definitions[LdapService]): Decoder[LdapAuthorizationRule.Settings] =
    Decoder
      .instance { c =>
        for {
          name <- c.downField("name").as[LdapService.Name]
          groups <- c.downField("groups").as[NonEmptySet[Group]]
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
            .map(LdapAuthorizationRule.Settings(_, groups, groups))
        case (name, None, groups) =>
          LdapRulesDecodersHelper
            .findLdapService[LdapAuthorizationService](ldapDefinitions.items, name, LdapAuthorizationRule.name)
            .map(LdapAuthorizationRule.Settings(_, groups, groups))
      }
      .decoder
}

class LdapAuthRuleDecoder(ldapDefinitions: Definitions[LdapService])
  extends RuleDecoderWithoutAssociatedFields[LdapAuthRule](
    LdapAuthRuleDecoder.instance(ldapDefinitions)
  )

object LdapAuthRuleDecoder {

  private def instance(ldapDefinitions: Definitions[LdapService]): Decoder[LdapAuthRule] =
    Decoder
      .instance { c =>
        for {
          name <- c.downField("name").as[LdapService.Name]
          groups <- c.downField("groups").as[NonEmptySet[Group]]
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
            .map(createLdapAuthRule(_, groups))
        case (name, None, groups) =>
          LdapRulesDecodersHelper
            .findLdapService[LdapAuthService](ldapDefinitions.items, name, LdapAuthRule.name)
            .map(createLdapAuthRule(_, groups))
      }
      .decoder

  private def createLdapAuthRule(ldapService: LdapAuthService, groups: NonEmptySet[Group]) = {
    new LdapAuthRule(
      new LdapAuthenticationRule(LdapAuthenticationRule.Settings(ldapService)),
      new LdapAuthorizationRule(LdapAuthorizationRule.Settings(ldapService, groups, groups))
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