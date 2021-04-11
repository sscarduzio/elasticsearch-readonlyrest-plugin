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
import tech.beshu.ror.accesscontrol.blocks.definitions._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.IndicesRule
import tech.beshu.ror.accesscontrol.blocks.rules.{AuthKeyRule, AuthKeySha1Rule, ExternalAuthenticationRule, JwtAuthRule, _}
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.{Definitions, DefinitionsPack}
import tech.beshu.ror.accesscontrol.factory.decoders.rules._
import tech.beshu.ror.providers.UuidProvider

object ruleDecoders {

  def ruleDecoderBy(name: Rule.Name,
                    definitions: DefinitionsPack,
                    globalSettings: GlobalSettings,
                    caseMappingEquality: UserIdCaseMappingEquality)
                   (implicit clock: Clock,
                    uuidProvider: UuidProvider): Option[RuleBaseDecoder[_ <: Rule] with RuleDecoder[_ <: Rule]] = {
    implicit val userIdEq: Eq[User.Id] = caseMappingEquality.toOrder
    name match {
      case ActionsRule.name => Some(ActionsRuleDecoder)
      case ApiKeysRule.name => Some(ApiKeysRuleDecoder)
      case FieldsRule.name => Some(new FieldsRuleDecoder(globalSettings.flsEngine))
      case ResponseFieldsRule.name => Some(ResponseFieldsRuleDecoder)
      case FilterRule.name => Some(FilterRuleDecoder)
      case GroupsRule.name => Some(new GroupsRuleDecoder(definitions.users, caseMappingEquality))
      case HeadersAndRule.name | HeadersAndRule.deprecatedName => Some(HeadersAndRuleDecoder)
      case HeadersOrRule.name => Some(HeadersOrRuleDecoder)
      case HostsRule.name => Some(HostsRuleDecoder)
      case IndicesRule.name => Some(IndicesRuleDecoders)
      case KibanaAccessRule.name => Some(new KibanaAccessRuleDecoder(globalSettings.configurationIndex))
      case KibanaHideAppsRule.name => Some(KibanaHideAppsRuleDecoder)
      case KibanaIndexRule.name => Some(KibanaIndexRuleDecoder)
      case KibanaTemplateIndexRule.name => Some(KibanaTemplateIndexRuleDecoder)
      case LocalHostsRule.name => Some(new LocalHostsRuleDecoder)
      case MaxBodyLengthRule.name => Some(MaxBodyLengthRuleDecoder)
      case MethodsRule.name => Some(MethodsRuleDecoder)
      case RepositoriesRule.name => Some(RepositoriesRuleDecoder)
      case SessionMaxIdleRule.name => Some(new SessionMaxIdleRuleDecoder())
      case SnapshotsRule.name => Some(SnapshotsRuleDecoder)
      case UriRegexRule.name => Some(UriRegexRuleDecoder)
      case UsersRule.name => Some(new UsersRuleDecoder()(caseMappingEquality))
      case XForwardedForRule.name => Some(XForwardedForRuleDecoder)
      case _ => usersDefinitionsAllowedRulesDecoderBy(
        name,
        definitions.authenticationServices,
        definitions.authorizationServices,
        definitions.proxies,
        definitions.jwts,
        definitions.rorKbns,
        definitions.ldaps,
        Some(definitions.impersonators),
        caseMappingEquality
      ) map(_.asInstanceOf[RuleBaseDecoder[_ <: Rule] with RuleDecoder[_ <: Rule]])
    }
  }

  def usersDefinitionsAllowedRulesDecoderBy(name: Rule.Name,
                                            authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
                                            authorizationServiceDefinitions: Definitions[ExternalAuthorizationService],
                                            authProxyDefinitions: Definitions[ProxyAuth],
                                            jwtDefinitions: Definitions[JwtDef],
                                            rorKbnDefinitions: Definitions[RorKbnDef],
                                            ldapServiceDefinitions: Definitions[LdapService],
                                            impersonatorsDefinitions: Option[Definitions[ImpersonatorDef]],
                                            caseMappingEquality: UserIdCaseMappingEquality): Option[RuleDecoder[_ <: Rule]] = {
    name match {
      case ExternalAuthorizationRule.name => Some(new ExternalAuthorizationRuleDecoder(authorizationServiceDefinitions, caseMappingEquality))
      case LdapAuthorizationRule.name => Some(new LdapAuthorizationRuleDecoder(ldapServiceDefinitions))
      case LdapAuthRule.name => Some(new LdapAuthRuleDecoder(ldapServiceDefinitions, caseMappingEquality))
      case RorKbnAuthRule.name => Some(new RorKbnAuthRuleDecoder(rorKbnDefinitions, caseMappingEquality))
      case _ =>
        authenticationRuleDecoderBy(
          name,
          authenticationServiceDefinitions,
          authProxyDefinitions,
          jwtDefinitions,
          ldapServiceDefinitions,
          rorKbnDefinitions,
          impersonatorsDefinitions,
          caseMappingEquality
        )  map(_.asInstanceOf[RuleDecoder[_ <: Rule]])
    }
  }

  def authenticationRuleDecoderBy(name: Rule.Name,
                                  authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
                                  authProxyDefinitions: Definitions[ProxyAuth],
                                  jwtDefinitions: Definitions[JwtDef],
                                  ldapServiceDefinitions: Definitions[LdapService],
                                  rorKbnDefinitions: Definitions[RorKbnDef],
                                  impersonatorsDefinitions: Option[Definitions[ImpersonatorDef]],
                                  caseMappingEquality: UserIdCaseMappingEquality): Option[AuthenticationRuleDecoder[_ <: AuthenticationRule]] = {
    name match {
      case AuthKeyRule.name => Some(new AuthKeyRuleDecoder(impersonatorsDefinitions, caseMappingEquality))
      case AuthKeySha1Rule.name => Some(new AuthKeySha1RuleDecoder(impersonatorsDefinitions, caseMappingEquality))
      case AuthKeySha256Rule.name => Some(new AuthKeySha256RuleDecoder(impersonatorsDefinitions, caseMappingEquality))
      case AuthKeySha512Rule.name => Some(new AuthKeySha512RuleDecoder(impersonatorsDefinitions, caseMappingEquality))
      case AuthKeyPBKDF2WithHmacSHA512Rule.name => Some(new AuthKeyPBKDF2WithHmacSHA512RuleDecoder(impersonatorsDefinitions, caseMappingEquality))
      case AuthKeyUnixRule.name => Some(new AuthKeyUnixRuleDecoder(impersonatorsDefinitions, caseMappingEquality))
      case ExternalAuthenticationRule.name => Some(new ExternalAuthenticationRuleDecoder(authenticationServiceDefinitions, caseMappingEquality))
      case JwtAuthRule.name => Some(new JwtAuthRuleDecoder(jwtDefinitions, caseMappingEquality))
      case LdapAuthenticationRule.name => Some(new LdapAuthenticationRuleDecoder(ldapServiceDefinitions, caseMappingEquality))
      case ProxyAuthRule.name => Some(new ProxyAuthRuleDecoder(authProxyDefinitions, caseMappingEquality))
      case _ => None
    }
  }


}
