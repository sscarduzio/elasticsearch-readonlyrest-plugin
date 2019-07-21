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
package tech.beshu.ror.acl.factory.decoders

import java.time.Clock

import tech.beshu.ror.acl.blocks.definitions._
import tech.beshu.ror.acl.blocks.definitions.ldap.LdapService
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.acl.blocks.rules._
import tech.beshu.ror.acl.factory.decoders.definitions.{Definitions, DefinitionsPack}
import tech.beshu.ror.acl.factory.decoders.rules._
import tech.beshu.ror.providers.{EnvVarsProvider, PropertiesProvider, UuidProvider}

import scala.language.{existentials, implicitConversions}

object ruleDecoders {

  implicit def ruleDecoderBy(name: Rule.Name,
                             definitions: DefinitionsPack)
                            (implicit clock: Clock,
                             uuidProvider: UuidProvider,
                             propertiesProvider: PropertiesProvider,
                             envVarsProvider: EnvVarsProvider): Option[RuleBaseDecoder[_ <: Rule]] =
    name match {
      case ActionsRule.name => Some(ActionsRuleDecoder)
      case ApiKeysRule.name => Some(ApiKeysRuleDecoder)
      case ExternalAuthorizationRule.name => Some(new ExternalAuthorizationRuleDecoder(definitions.authorizationServices))
      case FieldsRule.name => Some(FieldsRuleDecoder)
      case FilterRule.name => Some(new FilterRuleDecoder)
      case GroupsRule.name => Some(new GroupsRuleDecoder(definitions.users))
      case HeadersAndRule.name | HeadersAndRule.deprecatedName => Some(HeadersAndRuleDecoder)
      case HeadersOrRule.name => Some(HeadersOrRuleDecoder)
      case HostsRule.name => Some(new HostsRuleDecoder)
      case IndicesRule.name => Some(new IndicesRuleDecoders)
      case KibanaAccessRule.name => Some(new KibanaAccessRuleDecoder)
      case KibanaHideAppsRule.name => Some(KibanaHideAppsRuleDecoder)
      case KibanaIndexRule.name => Some(new KibanaIndexRuleDecoder)
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
      case _ => authenticationRuleDecoderBy(
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
                                  impersonatorDefs: Option[Definitions[ImpersonatorDef]]): Option[RuleBaseDecoder[_ <: AuthenticationRule]] = {
    name match {
      case AuthKeyRule.name => Some(new AuthKeyRuleDecoder(impersonatorDefs))
      case AuthKeySha1Rule.name => Some(new AuthKeySha1RuleDecoder(impersonatorDefs))
      case AuthKeySha256Rule.name => Some(new AuthKeySha256RuleDecoder(impersonatorDefs))
      case AuthKeySha512Rule.name => Some(new AuthKeySha512RuleDecoder(impersonatorDefs))
      case AuthKeyUnixRule.name => Some(new AuthKeyUnixRuleDecoder(impersonatorDefs))
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
