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

import cats.implicits._
import io.circe.{Decoder, DecodingFailure}
import tech.beshu.ror.accesscontrol.blocks.definitions._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.auth._
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch._
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices._
import tech.beshu.ror.accesscontrol.blocks.rules.http._
import tech.beshu.ror.accesscontrol.blocks.rules.kibana._
import tech.beshu.ror.accesscontrol.blocks.rules.tranport._
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler
import tech.beshu.ror.accesscontrol.domain.{GlobPattern, UserIdPatterns}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.{Definitions, DefinitionsPack}
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.elasticsearch._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.http._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.kibana._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.transport._
import tech.beshu.ror.accesscontrol.matchers.GenericPatternMatcher
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.configuration.EnvironmentConfig

object ruleDecoders {

  def ruleDecoderBy(name: Rule.Name,
                    definitions: DefinitionsPack,
                    globalSettings: GlobalSettings,
                    mocksProvider: MocksProvider)
                   (implicit environmentConfig: EnvironmentConfig): Option[RuleDecoder[Rule]] = {
    val variableCreator = new RuntimeResolvableVariableCreator(
      TransformationCompiler.withAliases(
        environmentConfig.variablesFunctions,
        definitions.variableTransformationAliases.items.map(_.alias)
      )
    )

    val optionalRuleDecoder = name match {
      case ActionsRule.Name.name => Some(ActionsRuleDecoder)
      case ApiKeysRule.Name.name => Some(ApiKeysRuleDecoder)
      case DataStreamsRule.Name.name => Some(new DataStreamsRuleDecoder(variableCreator))
      case FieldsRule.Name.name => Some(new FieldsRuleDecoder(globalSettings.flsEngine, variableCreator))
      case ResponseFieldsRule.Name.name => Some(new ResponseFieldsRuleDecoder(variableCreator))
      case FilterRule.Name.name => Some(new FilterRuleDecoder(variableCreator))
      case GroupsOrRule.Name.name => Some(new GroupsOrRuleDecoder(definitions.users, globalSettings, variableCreator)(GroupsOrRule.Name))
      case GroupsOrRule.DeprecatedName.name => Some(new GroupsOrRuleDecoder(definitions.users, globalSettings, variableCreator)(GroupsOrRule.DeprecatedName))
      case GroupsAndRule.Name.name => Some(new GroupsAndRuleDecoder(definitions.users, globalSettings, variableCreator))
      case HeadersAndRule.Name.name => Some(new HeadersAndRuleDecoder()(HeadersAndRule.Name))
      case HeadersAndRule.DeprecatedName.name => Some(new HeadersAndRuleDecoder()(HeadersAndRule.DeprecatedName))
      case HeadersOrRule.Name.name => Some(HeadersOrRuleDecoder)
      case HostsRule.Name.name => Some(new HostsRuleDecoder(variableCreator))
      case IndicesRule.Name.name => Some(new IndicesRuleDecoders(variableCreator, environmentConfig.uniqueIdentifierGenerator))
      case KibanaUserDataRule.Name.name => Some(new KibanaUserDataRuleDecoder(globalSettings.configurationIndex, variableCreator)(environmentConfig.jsCompiler))
      case KibanaAccessRule.Name.name => Some(new KibanaAccessRuleDecoder(globalSettings.configurationIndex))
      case KibanaHideAppsRule.Name.name => Some(new KibanaHideAppsRuleDecoder()(environmentConfig.jsCompiler))
      case KibanaIndexRule.Name.name => Some(new KibanaIndexRuleDecoder(variableCreator))
      case KibanaTemplateIndexRule.Name.name => Some(new KibanaTemplateIndexRuleDecoder(variableCreator))
      case LocalHostsRule.Name.name => Some(new LocalHostsRuleDecoder(variableCreator))
      case MaxBodyLengthRule.Name.name => Some(MaxBodyLengthRuleDecoder)
      case MethodsRule.Name.name => Some(MethodsRuleDecoder)
      case RepositoriesRule.Name.name => Some(new RepositoriesRuleDecoder(variableCreator))
      case SessionMaxIdleRule.Name.name => Some(new SessionMaxIdleRuleDecoder(globalSettings)(environmentConfig.clock, environmentConfig.uuidProvider))
      case SnapshotsRule.Name.name => Some(new SnapshotsRuleDecoder(variableCreator))
      case UriRegexRule.Name.name => Some(new UriRegexRuleDecoder(variableCreator))
      case UsersRule.Name.name => Some(new UsersRuleDecoder(globalSettings, variableCreator))
      case XForwardedForRule.Name.name => Some(new XForwardedForRuleDecoder(variableCreator))
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
        globalSettings
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
                                            globalSettings: GlobalSettings): Option[RuleDecoder[Rule]] = {
    val optionalRuleDecoder = name match {
      case ExternalAuthorizationRule.Name.name =>
        Some(new ExternalAuthorizationRuleDecoder(authorizationServiceDefinitions, impersonatorsDefinitions, mocksProvider, globalSettings))
      case JwtAuthRule.Name.name =>
        Some(new JwtAuthRuleDecoder(jwtDefinitions, globalSettings))
      case LdapAuthorizationRule.Name.name =>
        Some(new LdapAuthorizationRuleDecoder(ldapServiceDefinitions, impersonatorsDefinitions, mocksProvider, globalSettings))
      case LdapAuthRule.Name.name =>
        Some(new LdapAuthRuleDecoder(ldapServiceDefinitions, impersonatorsDefinitions, mocksProvider, globalSettings))
      case RorKbnAuthRule.Name.name =>
        Some(new RorKbnAuthRuleDecoder(rorKbnDefinitions, globalSettings))
      case _ =>
        authenticationRuleDecoderBy(
          name,
          authenticationServiceDefinitions,
          authProxyDefinitions,
          ldapServiceDefinitions,
          impersonatorsDefinitions,
          mocksProvider,
          globalSettings
        )
    }
    optionalRuleDecoder.map(_.asInstanceOf[RuleDecoder[Rule]])
  }

  def authenticationRuleDecoderBy(name: Rule.Name,
                                  authenticationServiceDefinitions: Definitions[ExternalAuthenticationService],
                                  authProxyDefinitions: Definitions[ProxyAuth],
                                  ldapServiceDefinitions: Definitions[LdapService],
                                  impersonatorsDefinitions: Option[Definitions[ImpersonatorDef]],
                                  mocksProvider: MocksProvider,
                                  globalSettings: GlobalSettings): Option[RuleDecoder[AuthenticationRule]] = {
    val optionalRuleDecoder = name match {
      case AuthKeyRule.Name.name =>
        Some(new AuthKeyRuleDecoder(impersonatorsDefinitions, mocksProvider, globalSettings))
      case AuthKeySha1Rule.Name.name =>
        Some(new AuthKeySha1RuleDecoder(impersonatorsDefinitions, mocksProvider, globalSettings))
      case AuthKeySha256Rule.Name.name =>
        Some(new AuthKeySha256RuleDecoder(impersonatorsDefinitions, mocksProvider, globalSettings))
      case AuthKeySha512Rule.Name.name =>
        Some(new AuthKeySha512RuleDecoder(impersonatorsDefinitions, mocksProvider, globalSettings))
      case AuthKeyPBKDF2WithHmacSHA512Rule.Name.name =>
        Some(new AuthKeyPBKDF2WithHmacSHA512RuleDecoder(impersonatorsDefinitions, mocksProvider, globalSettings))
      case AuthKeyUnixRule.Name.name =>
        Some(new AuthKeyUnixRuleDecoder(impersonatorsDefinitions, mocksProvider, globalSettings))
      case ExternalAuthenticationRule.Name.name =>
        Some(new ExternalAuthenticationRuleDecoder(authenticationServiceDefinitions, impersonatorsDefinitions, mocksProvider, globalSettings))
      case LdapAuthenticationRule.Name.name =>
        Some(new LdapAuthenticationRuleDecoder(ldapServiceDefinitions, impersonatorsDefinitions, mocksProvider, globalSettings))
      case ProxyAuthRule.Name.name =>
        Some(new ProxyAuthRuleDecoder(authProxyDefinitions, impersonatorsDefinitions, mocksProvider, globalSettings))
      case TokenAuthenticationRule.Name.name =>
        Some(new TokenAuthenticationRuleDecoder(impersonatorsDefinitions, mocksProvider, globalSettings))
      case _ => None
    }
    optionalRuleDecoder
      .map(_.asInstanceOf[RuleDecoder[AuthenticationRule]])
  }

  def withUserIdParamsCheck[R <: Rule](decoder: RuleDecoder[R],
                                       userIdPatterns: UserIdPatterns,
                                       globalSettings: GlobalSettings,
                                       errorCreator: Message => DecodingFailure): Decoder[RuleDecoder.Result[R]] = {
    decoder.flatMap { result =>
      result.rule.rule match {
        case _: Rule.RegularRule => Decoder.const(result)
        case _: Rule.AuthorizationRule => Decoder.const(result)
        case rule: AuthenticationRule =>
          checkUsersEligibility(rule, userIdPatterns, globalSettings) match {
            case Right(_) => Decoder.const(result)
            case Left(msg) => Decoder.failed(errorCreator(Message(msg)))
          }
      }
    }
  }

  private def checkUsersEligibility(rule: AuthenticationRule,
                                    userIdPatterns: UserIdPatterns,
                                    globalSettings: GlobalSettings) = {
    rule.eligibleUsers match {
      case EligibleUsersSupport.Available(users) =>
        implicit val caseSensitivity: GlobPattern.CaseSensitivity = globalSettings.userIdCaseSensitivity
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
