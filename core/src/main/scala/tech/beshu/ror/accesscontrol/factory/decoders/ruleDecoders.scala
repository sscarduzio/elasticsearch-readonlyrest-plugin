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

import tech.beshu.ror.accesscontrol.blocks.definitions._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules._
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.{Definitions, DefinitionsPack}
import tech.beshu.ror.accesscontrol.factory.decoders.rules._
import tech.beshu.ror.providers.UuidProvider

import java.time.Clock

object ruleDecoders {

  def ruleDecoderBy(name: Rule.Name,
                    definitions: DefinitionsPack,
                    rorIndexNameConfiguration: RorConfigurationIndex,
                    globalSettings: GlobalSettings)
                   (implicit clock: Clock,
                    uuidProvider: UuidProvider,
                    caseMappingEquality: UserIdCaseMappingEquality): Option[RuleBaseDecoder[_ <: Rule]] =
    name match {
      case ActionsRule.name => Some(ActionsRuleDecoder)
      case ApiKeysRule.name => Some(ApiKeysRuleDecoder)
      case ExternalAuthorizationRule.name => Some(new ExternalAuthorizationRuleDecoder(definitions.authorizationServices, caseMappingEquality))
      case FieldsRule.name => Some(new FieldsRuleDecoder(globalSettings.flsEngine))
      case ResponseFieldsRule.name => Some(ResponseFieldsRuleDecoder)
      case FilterRule.name => Some(new FilterRuleDecoder)
      case GroupsRule.name => Some(new GroupsRuleDecoder(definitions.users, caseMappingEquality))
      case HeadersAndRule.name | HeadersAndRule.deprecatedName => Some(HeadersAndRuleDecoder)
      case HeadersOrRule.name => Some(HeadersOrRuleDecoder)
      case HostsRule.name => Some(new HostsRuleDecoder)
      case IndicesRule.name => Some(new IndicesRuleDecoders)
      case KibanaAccessRule.name => Some(new KibanaAccessRuleDecoder(rorIndexNameConfiguration))
      case KibanaHideAppsRule.name => Some(KibanaHideAppsRuleDecoder)
      case KibanaIndexRule.name => Some(new KibanaIndexRuleDecoder)
      case KibanaTemplateIndexRule.name => Some(new KibanaTemplateIndexRuleDecoder)
      case LdapAuthorizationRule.name => Some(new LdapAuthorizationRuleDecoder(definitions.ldaps))
      case LocalHostsRule.name => Some(new LocalHostsRuleDecoder)
      case MaxBodyLengthRule.name => Some(MaxBodyLengthRuleDecoder)
      case MethodsRule.name => Some(MethodsRuleDecoder)
      case RepositoriesRule.name => Some(new RepositoriesRuleDecoder)
      case SessionMaxIdleRule.name => Some(new SessionMaxIdleRuleDecoder)
      case SnapshotsRule.name => Some(new SnapshotsRuleDecoder)
      case UriRegexRule.name => Some(new UriRegexRuleDecoder)
      case UsersRule.name => Some(new UsersRuleDecoder()(caseMappingEquality))
      case XForwardedForRule.name => Some(new XForwardedForRuleDecoder)
      case _ =>
        authenticationRuleDecoderBy(
          name,
          definitions.authenticationServices,
          definitions.proxies,
          definitions.jwts,
          definitions.ldaps,
          definitions.rorKbns,
          Some(definitions.impersonators),
          caseMappingEquality
        )
    }

  def authenticationRuleDecoderBy(name: Rule.Name,
                                  authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
                                  authProxyDefinitions: Definitions[ProxyAuth],
                                  jwtDefinitions: Definitions[JwtDef],
                                  ldapServiceDefinitions: Definitions[LdapService],
                                  rorKbnDefinitions: Definitions[RorKbnDef],
                                  impersonatorsDefinitions: Option[Definitions[ImpersonatorDef]],
                                  caseMappingEquality: UserIdCaseMappingEquality): Option[RuleBaseDecoder[_ <: AuthenticationRule]] = {
    name match {
      case AuthKeyRule.name => Some(new AuthKeyRuleDecoder(impersonatorsDefinitions, caseMappingEquality))
      case AuthKeySha1Rule.name => Some(new AuthKeySha1RuleDecoder(impersonatorsDefinitions, caseMappingEquality))
      case AuthKeySha256Rule.name => Some(new AuthKeySha256RuleDecoder(impersonatorsDefinitions, caseMappingEquality))
      case AuthKeySha512Rule.name => Some(new AuthKeySha512RuleDecoder(impersonatorsDefinitions, caseMappingEquality))
      case AuthKeyPBKDF2WithHmacSHA512Rule.name => Some(new AuthKeyPBKDF2WithHmacSHA512RuleDecoder(impersonatorsDefinitions, caseMappingEquality))
      case AuthKeyUnixRule.name => Some(new AuthKeyUnixRuleDecoder(impersonatorsDefinitions, caseMappingEquality))
      case ExternalAuthenticationRule.name => Some(new ExternalAuthenticationRuleDecoder(authenticationServiceDefinitions, caseMappingEquality))
      case JwtAuthRule.name => Some(new JwtAuthRuleDecoder(jwtDefinitions, caseMappingEquality))
      case LdapAuthRule.name => Some(new LdapAuthRuleDecoder(ldapServiceDefinitions, caseMappingEquality))
      case LdapAuthenticationRule.name => Some(new LdapAuthenticationRuleDecoder(ldapServiceDefinitions, caseMappingEquality))
      case ProxyAuthRule.name => Some(new ProxyAuthRuleDecoder(authProxyDefinitions, caseMappingEquality))
      case RorKbnAuthRule.name => Some(new RorKbnAuthRuleDecoder(rorKbnDefinitions, caseMappingEquality))
      case _ => None
    }
  }

}
