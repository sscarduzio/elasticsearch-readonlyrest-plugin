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

import tech.beshu.ror.accesscontrol.blocks.definitions._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules._
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.{Definitions, DefinitionsPack}
import tech.beshu.ror.accesscontrol.factory.decoders.rules._
import tech.beshu.ror.configuration.loader.RorConfigurationIndex
import tech.beshu.ror.providers.UuidProvider

object ruleDecoders {

  implicit def ruleDecoderBy(name: Rule.Name,
                             definitions: DefinitionsPack,
                             rorIndexNameConfiguration: RorConfigurationIndex,
                             globalSettings: GlobalSettings)
                            (implicit clock: Clock,
                             uuidProvider: UuidProvider): Option[RuleBaseDecoder[_ <: Rule]] =
    name match {
      case ActionsRule.name => Some(ActionsRuleDecoder)
      case ApiKeysRule.name => Some(ApiKeysRuleDecoder)
      case ExternalAuthorizationRule.name => Some(new ExternalAuthorizationRuleDecoder(definitions.authorizationServices))
      case FieldsRule.name => Some(new FieldsRuleDecoder(globalSettings.flsEngine))
      case FilterRule.name => Some(new FilterRuleDecoder)
      case GroupsRule.name => Some(new GroupsRuleDecoder(definitions.users))
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
      case UsersRule.name => Some(new UsersRuleDecoder)
      case XForwardedForRule.name => Some(new XForwardedForRuleDecoder)
      case _ =>
        authenticationRuleDecoderBy(
          name,
          definitions.authenticationServices,
          definitions.proxies,
          definitions.jwts,
          definitions.ldaps,
          definitions.rorKbns,
          Some(definitions.impersonators)
        )
    }

  def authenticationRuleDecoderBy(name: Rule.Name,
                                  authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
                                  authProxyDefinitions: Definitions[ProxyAuth],
                                  jwtDefinitions: Definitions[JwtDef],
                                  ldapServiceDefinitions: Definitions[LdapService],
                                  rorKbnDefinitions: Definitions[RorKbnDef],
                                  impersonatorsDefinitions: Option[Definitions[ImpersonatorDef]]): Option[RuleBaseDecoder[_ <: AuthenticationRule]] = {
    name match {
      case AuthKeyRule.name => Some(new AuthKeyRuleDecoder(impersonatorsDefinitions))
      case AuthKeySha1Rule.name => Some(new AuthKeySha1RuleDecoder(impersonatorsDefinitions))
      case AuthKeySha256Rule.name => Some(new AuthKeySha256RuleDecoder(impersonatorsDefinitions))
      case AuthKeySha512Rule.name => Some(new AuthKeySha512RuleDecoder(impersonatorsDefinitions))
      case AuthKeyPBKDF2WithHmacSHA512Rule.name => Some(new AuthKeyPBKDF2WithHmacSHA512RuleDecoder(impersonatorsDefinitions))
      case AuthKeyUnixRule.name => Some(new AuthKeyUnixRuleDecoder(impersonatorsDefinitions))
      case ExternalAuthenticationRule.name => Some(new ExternalAuthenticationRuleDecoder(authenticationServiceDefinitions))
      case JwtAuthRule.name => Some(new JwtAuthRuleDecoder(jwtDefinitions))
      case LdapAuthRule.name => Some(new LdapAuthRuleDecoder(ldapServiceDefinitions))
      case LdapAuthenticationRule.name => Some(new LdapAuthenticationRuleDecoder(ldapServiceDefinitions))
      case ProxyAuthRule.name => Some(new ProxyAuthRuleDecoder(authProxyDefinitions))
      case RorKbnAuthRule.name => Some(new RorKbnAuthRuleDecoder(rorKbnDefinitions))
      case _ => None
    }
  }

}
