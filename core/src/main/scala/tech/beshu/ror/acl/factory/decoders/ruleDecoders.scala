package tech.beshu.ror.acl.factory.decoders

import java.time.Clock

import tech.beshu.ror.acl.blocks.definitions.ProxyAuth
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.acl.blocks.rules._
import tech.beshu.ror.acl.factory.decoders.definitions.{Definitions, DefinitionsPack}
import tech.beshu.ror.acl.factory.decoders.rules._
import tech.beshu.ror.acl.utils.UuidProvider

import scala.language.implicitConversions

object ruleDecoders {

  implicit def ruleDecoderBy(name: Rule.Name, definitions: DefinitionsPack)
                            (implicit clock: Clock, uuidProvider: UuidProvider): Option[RuleBaseDecoder[_ <: Rule]] =
    name match {
      case ActionsRule.name => Some(ActionsRuleDecoder)
      case ApiKeysRule.name => Some(ApiKeysRuleDecoder)
      case ExternalAuthenticationRule.name => Some(new ExternalAuthenticationRuleDecoder(definitions.authenticationServices))
      case ExternalAuthorizationRule.name => Some(new ExternalAuthorizationRuleDecoder(definitions.authorizationServices))
      case FieldsRule.name => Some(FieldsRuleDecoder)
      case FilterRule.name => Some(FilterRuleDecoder)
      case GroupsRule.name => Some(new GroupsRuleDecoder(definitions.users))
      case HeadersAndRule.name => Some(HeadersAndRuleDecoder)
      case HeadersOrRule.name => Some(HeadersOrRuleDecoder)
      case HostsRule.name => Some(HostsRuleDecoder)
      case IndicesRule.name => Some(IndicesRuleDecoders)
      case KibanaAccessRule.name => Some(KibanaAccessRuleDecoder)
      case KibanaHideAppsRule.name => Some(KibanaHideAppsRuleDecoder)
      case KibanaIndexRule.name => Some(KibanaIndexRuleDecoder)
      case LocalHostsRule.name => Some(LocalHostsRuleDecoder)
      case MaxBodyLengthRule.name => Some(MaxBodyLengthRuleDecoder)
      case MethodsRule.name => Some(MethodsRuleDecoder)
      case SessionMaxIdleRule.name => Some(new SessionMaxIdleRuleDecoder)
      case UriRegexRule.name => Some(UriRegexRuleDecoder)
      case UsersRule.name => Some(UsersRuleDecoder)
      case XForwardedForRule.name => Some(XForwardedForRuleDecoder)
      case _ => authenticationRuleDecoderBy(name, definitions.proxies)
    }

  def authenticationRuleDecoderBy(name: Rule.Name,
                                  authProxyDefinitions: Definitions[ProxyAuth]): Option[RuleBaseDecoder[_ <: AuthenticationRule]] = {
    name match {
      case AuthKeyRule.name => Some(AuthKeyRuleDecoder)
      case AuthKeySha1Rule.name => Some(AuthKeySha1RuleDecoder)
      case AuthKeySha256Rule.name => Some(AuthKeySha256RuleDecoder)
      case AuthKeySha512Rule.name => Some(AuthKeySha512RuleDecoder)
      case AuthKeyUnixRule.name => Some(AuthKeyUnixRuleDecoder)
      case ProxyAuthRule.name => Some(new ProxyAuthRuleDecoder(authProxyDefinitions))
      case _ => None
    }
  }
}
