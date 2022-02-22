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
package tech.beshu.ror.accesscontrol.factory.decoders

import java.time.Clock

import cats.Eq
import cats.implicits._
import io.circe.{Decoder, DecodingFailure}
import tech.beshu.ror.accesscontrol.blocks.definitions._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules._
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.AuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.IndicesRule
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{User, UserIdPatterns}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.{Definitions, DefinitionsPack}
import tech.beshu.ror.accesscontrol.factory.decoders.rules._
import tech.beshu.ror.accesscontrol.matchers.GenericPatternMatcher
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.providers.UuidProvider

object ruleDecoders {

  def ruleDecoderBy(name: Rule.Name,
                    definitions: DefinitionsPack,
                    globalSettings: GlobalSettings,
                    mocksProvider: MocksProvider,
                    caseMappingEquality: UserIdCaseMappingEquality)
                   (implicit clock: Clock,
                    uuidProvider: UuidProvider): Option[RuleDecoder[Rule]] = {
    implicit val userIdEq: Eq[User.Id] = caseMappingEquality.toOrder
    val optionalRuleDecoder = name match {
      case ActionsRule.Name.name => Some(ActionsRuleDecoder)
      case ApiKeysRule.Name.name => Some(ApiKeysRuleDecoder)
      case FieldsRule.Name.name => Some(new FieldsRuleDecoder(globalSettings.flsEngine))
      case ResponseFieldsRule.Name.name => Some(ResponseFieldsRuleDecoder)
      case FilterRule.Name.name => Some(FilterRuleDecoder)
      case GroupsRule.Name.name => Some(new GroupsRuleDecoder(definitions.users, caseMappingEquality))
      case GroupsAndRule.Name.name => Some(new GroupsRuleDecoder(definitions.users, caseMappingEquality))
      case HeadersAndRule.Name.name => Some(new HeadersAndRuleDecoder()(HeadersAndRule.Name))
      case HeadersAndRule.DeprecatedName.name => Some(new HeadersAndRuleDecoder()(HeadersAndRule.DeprecatedName))
      case HeadersOrRule.Name.name => Some(HeadersOrRuleDecoder)
      case HostsRule.Name.name => Some(HostsRuleDecoder)
      case IndicesRule.Name.name => Some(IndicesRuleDecoders)
      case KibanaAccessRule.Name.name => Some(new KibanaAccessRuleDecoder(globalSettings.configurationIndex))
      case KibanaHideAppsRule.Name.name => Some(KibanaHideAppsRuleDecoder)
      case KibanaIndexRule.Name.name => Some(KibanaIndexRuleDecoder)
      case KibanaTemplateIndexRule.Name.name => Some(KibanaTemplateIndexRuleDecoder)
      case LocalHostsRule.Name.name => Some(new LocalHostsRuleDecoder)
      case MaxBodyLengthRule.Name.name => Some(MaxBodyLengthRuleDecoder)
      case MethodsRule.Name.name => Some(MethodsRuleDecoder)
      case RepositoriesRule.Name.name => Some(RepositoriesRuleDecoder)
      case SessionMaxIdleRule.Name.name => Some(new SessionMaxIdleRuleDecoder())
      case SnapshotsRule.Name.name => Some(SnapshotsRuleDecoder)
      case UriRegexRule.Name.name => Some(UriRegexRuleDecoder)
      case UsersRule.Name.name => Some(new UsersRuleDecoder()(caseMappingEquality))
      case XForwardedForRule.Name.name => Some(XForwardedForRuleDecoder)
      case _ => usersDefinitionsAllowedRulesDecoderBy(
        name,
        definitions.authenticationServices,
        definitions.authorizationServices,
        definitions.proxies,
        definitions.jwts,
        definitions.rorKbns,
        definitions.ldaps,
        Some(definitions.impersonators),
        mocksProvider,
        caseMappingEquality
      )
    }
    optionalRuleDecoder.map(_.asInstanceOf[RuleDecoder[Rule]])
  }

  def usersDefinitionsAllowedRulesDecoderBy(name: Rule.Name,
                                            authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
                                            authorizationServiceDefinitions: Definitions[ExternalAuthorizationService],
                                            authProxyDefinitions: Definitions[ProxyAuth],
                                            jwtDefinitions: Definitions[JwtDef],
                                            rorKbnDefinitions: Definitions[RorKbnDef],
                                            ldapServiceDefinitions: Definitions[LdapService],
                                            impersonatorsDefinitions: Option[Definitions[ImpersonatorDef]],
                                            mocksProvider: MocksProvider,
                                            caseMappingEquality: UserIdCaseMappingEquality): Option[RuleDecoder[Rule]] = {
    val optionalRuleDecoder = name match {
      case ExternalAuthorizationRule.Name.name =>
        Some(new ExternalAuthorizationRuleDecoder(authorizationServiceDefinitions, impersonatorsDefinitions, mocksProvider, caseMappingEquality))
      case LdapAuthorizationRule.Name.name =>
        Some(new LdapAuthorizationRuleDecoder(ldapServiceDefinitions, impersonatorsDefinitions, mocksProvider, caseMappingEquality))
      case LdapAuthRule.Name.name =>
        Some(new LdapAuthRuleDecoder(ldapServiceDefinitions, impersonatorsDefinitions, mocksProvider, caseMappingEquality))
      case RorKbnAuthRule.Name.name =>
        Some(new RorKbnAuthRuleDecoder(rorKbnDefinitions, caseMappingEquality))
      case _ =>
        authenticationRuleDecoderBy(
          name,
          authenticationServiceDefinitions,
          authProxyDefinitions,
          jwtDefinitions,
          ldapServiceDefinitions,
          rorKbnDefinitions,
          impersonatorsDefinitions,
          mocksProvider,
          caseMappingEquality
        )
    }
    optionalRuleDecoder.map(_.asInstanceOf[RuleDecoder[Rule]])
  }

  def authenticationRuleDecoderBy(name: Rule.Name,
                                  authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
                                  authProxyDefinitions: Definitions[ProxyAuth],
                                  jwtDefinitions: Definitions[JwtDef],
                                  ldapServiceDefinitions: Definitions[LdapService],
                                  rorKbnDefinitions: Definitions[RorKbnDef],
                                  impersonatorsDefinitions: Option[Definitions[ImpersonatorDef]],
                                  mocksProvider: MocksProvider,
                                  caseMappingEquality: UserIdCaseMappingEquality): Option[RuleDecoder[AuthenticationRule]] = {
    val optionalRuleDecoder = name match {
      case AuthKeyRule.Name.name =>
        Some(new AuthKeyRuleDecoder(impersonatorsDefinitions, mocksProvider, caseMappingEquality))
      case AuthKeySha1Rule.Name.name =>
        Some(new AuthKeySha1RuleDecoder(impersonatorsDefinitions, mocksProvider, caseMappingEquality))
      case AuthKeySha256Rule.Name.name =>
        Some(new AuthKeySha256RuleDecoder(impersonatorsDefinitions, mocksProvider, caseMappingEquality))
      case AuthKeySha512Rule.Name.name =>
        Some(new AuthKeySha512RuleDecoder(impersonatorsDefinitions, mocksProvider, caseMappingEquality))
      case AuthKeyPBKDF2WithHmacSHA512Rule.Name.name =>
        Some(new AuthKeyPBKDF2WithHmacSHA512RuleDecoder(impersonatorsDefinitions, mocksProvider, caseMappingEquality))
      case AuthKeyUnixRule.Name.name =>
        Some(new AuthKeyUnixRuleDecoder(impersonatorsDefinitions, mocksProvider, caseMappingEquality))
      case ExternalAuthenticationRule.Name.name =>
        Some(new ExternalAuthenticationRuleDecoder(authenticationServiceDefinitions, impersonatorsDefinitions, mocksProvider, caseMappingEquality))
      case JwtAuthRule.Name.name =>
        Some(new JwtAuthRuleDecoder(jwtDefinitions, caseMappingEquality))
      case LdapAuthenticationRule.Name.name =>
        Some(new LdapAuthenticationRuleDecoder(ldapServiceDefinitions, impersonatorsDefinitions, mocksProvider, caseMappingEquality))
      case ProxyAuthRule.Name.name =>
        Some(new ProxyAuthRuleDecoder(authProxyDefinitions, impersonatorsDefinitions, mocksProvider, caseMappingEquality))
      case _ => None
    }
    optionalRuleDecoder
      .map(_.asInstanceOf[RuleDecoder[AuthenticationRule]])
  }

  def withUserIdParamsCheck[R <: Rule](decoder: RuleDecoder[R],
                                       userIdPatterns: UserIdPatterns,
                                       errorCreator: Message => DecodingFailure): Decoder[RuleDecoder.Result[R]] = {
    decoder.flatMap { result =>
      result.rule.rule match {
        case _: Rule.RegularRule => Decoder.const(result)
        case _: Rule.AuthorizationRule => Decoder.const(result)
        case rule: AuthenticationRule =>
          checkUsersEligibility(rule, userIdPatterns) match {
            case Right(_) => Decoder.const(result)
            case Left(msg) => Decoder.failed(errorCreator(Message(msg)))
          }
      }
    }
  }

  private def checkUsersEligibility(rule: AuthenticationRule, userIdPatterns: UserIdPatterns) = {
    rule.eligibleUsers match {
      case EligibleUsersSupport.Available(users) =>
        implicit val _ = rule.caseMappingEquality
        val matcher = new GenericPatternMatcher(userIdPatterns.patterns.toList)
        if (users.exists(matcher.`match`)) {
          Right(())
        } else {
          Left(
            s"Users [${users.map(_.show).mkString(",")}] are allowed to be authenticated by rule [${rule.name.show}], but it's used in a context of user patterns [${userIdPatterns.show}]. It seems that this is not what you expect."
          )
        }
      case EligibleUsersSupport.NotAvailable =>
        Right(())
    }
  }
}
