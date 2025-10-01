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

import io.circe.{Decoder, DecodingFailure}
import tech.beshu.ror.accesscontrol.blocks.definitions.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, RuleName}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.*
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseGroupsRule
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.*
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.*
import tech.beshu.ror.accesscontrol.blocks.rules.http.*
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.*
import tech.beshu.ror.accesscontrol.blocks.rules.tranport.*
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, GroupsLogic, UserIdPatterns}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.{Definitions, DefinitionsPack}
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.*
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups.GroupsRuleDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.rules.elasticsearch.*
import tech.beshu.ror.accesscontrol.factory.decoders.rules.http.*
import tech.beshu.ror.accesscontrol.factory.decoders.rules.kibana.*
import tech.beshu.ror.accesscontrol.factory.decoders.rules.transport.*
import tech.beshu.ror.accesscontrol.matchers.GenericPatternMatcher
import tech.beshu.ror.SystemContext
import tech.beshu.ror.implicits.*

object ruleDecoders {

  def ruleDecoderBy(name: Rule.Name,
                    definitions: DefinitionsPack,
                    globalSettings: GlobalSettings,
                    mocksProvider: MocksProvider)
                   (implicit systemContext: SystemContext): Option[RuleDecoder[Rule]] = {
    val variableCreator = new RuntimeResolvableVariableCreator(
      TransformationCompiler.withAliases(
        systemContext.variablesFunctions,
        definitions.variableTransformationAliases.items.map(_.alias)
      )
    )

    val optionalRuleDecoder = name match {
      case ruleName@(
        Rule.Name("groups") |
        AllOfGroupsRule.DeprecatedSimpleSyntaxNameV1.name |
        AllOfGroupsRule.DeprecatedSimpleSyntaxNameV2.name |
        AllOfGroupsRule.SimpleSyntaxName.name |
        AnyOfGroupsRule.DeprecatedSimpleSyntaxNameV1.name |
        AnyOfGroupsRule.DeprecatedSimpleSyntaxNameV2.name |
        AnyOfGroupsRule.DeprecatedSimpleSyntaxNameV3.name |
        AnyOfGroupsRule.SimpleSyntaxName.name |
        NotAllOfGroupsRule.SimpleSyntaxName.name |
        NotAnyOfGroupsRule.SimpleSyntaxName.name) =>
        implicit val ruleNameForDecoder: RuleName[BaseGroupsRule[GroupsLogic]] = new RuleName[BaseGroupsRule[GroupsLogic]] {
          override val name: Rule.Name = ruleName
        }
        Some(new GroupsRuleDecoder(definitions.users, globalSettings, variableCreator))
      case ActionsRule.Name.name => Some(ActionsRuleDecoder)
      case ApiKeysRule.Name.name => Some(ApiKeysRuleDecoder)
      case DataStreamsRule.Name.name => Some(new DataStreamsRuleDecoder(variableCreator))
      case FieldsRule.Name.name => Some(new FieldsRuleDecoder(globalSettings.flsEngine, variableCreator))
      case ResponseFieldsRule.Name.name => Some(new ResponseFieldsRuleDecoder(variableCreator))
      case FilterRule.Name.name => Some(new FilterRuleDecoder(variableCreator))
      case HeadersAndRule.Name.name => Some(new HeadersAndRuleDecoder()(HeadersAndRule.Name))
      case HeadersAndRule.DeprecatedName.name => Some(new HeadersAndRuleDecoder()(HeadersAndRule.DeprecatedName))
      case HeadersOrRule.Name.name => Some(HeadersOrRuleDecoder)
      case HostsRule.Name.name => Some(new HostsRuleDecoder(variableCreator))
      case IndicesRule.Name.name => Some(new IndicesRuleDecoders(variableCreator, systemContext.uniqueIdentifierGenerator))
      case KibanaUserDataRule.Name.name => Some(new KibanaUserDataRuleDecoder(globalSettings.settingsIndex, variableCreator)(systemContext.jsCompiler))
      case KibanaAccessRule.Name.name => Some(new KibanaAccessRuleDecoder(globalSettings.settingsIndex))
      case KibanaHideAppsRule.Name.name => Some(new KibanaHideAppsRuleDecoder()(systemContext.jsCompiler))
      case KibanaIndexRule.Name.name => Some(new KibanaIndexRuleDecoder(variableCreator))
      case KibanaTemplateIndexRule.Name.name => Some(new KibanaTemplateIndexRuleDecoder(variableCreator))
      case LocalHostsRule.Name.name => Some(new LocalHostsRuleDecoder(variableCreator))
      case MaxBodyLengthRule.Name.name => Some(MaxBodyLengthRuleDecoder)
      case MethodsRule.Name.name => Some(MethodsRuleDecoder)
      case RepositoriesRule.Name.name => Some(new RepositoriesRuleDecoder(variableCreator))
      case SessionMaxIdleRule.Name.name => Some(new SessionMaxIdleRuleDecoder(globalSettings)(systemContext.clock, systemContext.uuidProvider))
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
        implicit val userIdCaseSensitivity: CaseSensitivity = globalSettings.userIdCaseSensitivity
        val matcher = new GenericPatternMatcher(userIdPatterns.patterns.toList)
        if (users.exists(matcher.`match`)) {
          Right(())
        } else {
          Left(
            s"Users [${users.show}] are allowed to be authenticated by rule [${rule.name.show}], but it's used in a context of user patterns [${userIdPatterns.show}]. It seems that this is not what you expect."
          )
        }
      case EligibleUsersSupport.NotAvailable =>
        Right(())
    }
  }
}
