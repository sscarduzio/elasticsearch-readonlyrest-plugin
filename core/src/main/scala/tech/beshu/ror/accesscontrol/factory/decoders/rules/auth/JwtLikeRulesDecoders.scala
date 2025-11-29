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
package tech.beshu.ror.accesscontrol.factory.decoders.rules.auth

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.ImpersonationWarning.ImpersonationWarningSupport
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthRule, AuthenticationRule, AuthorizationRule, RuleName}
import tech.beshu.ror.accesscontrol.blocks.users.LocalUsersContext.LocalUsersSupport
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.VariableUsage
import tech.beshu.ror.accesscontrol.domain.GroupsLogic
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common.nonEmptyStringDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups.GroupsLogicDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups.GroupsLogicRepresentationDecoder.GroupsLogicDecodingResult
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.implicits.*

// Common decoder for JWT rules and ROR KBN rules. They are very similar, and their decoding logic is mostly the same.
trait JwtLikeRulesDecoders[
  AUTHN_RULE <: AuthenticationRule : RuleName : VariableUsage : LocalUsersSupport : ImpersonationWarningSupport,
  AUTHZ_RULE <: AuthorizationRule : RuleName : VariableUsage : LocalUsersSupport : ImpersonationWarningSupport,
  AUTHZ_WITHOUT_GROUPS_RULE <: AuthorizationRule,
  AUTH_RULE <: AuthRule : RuleName : VariableUsage : LocalUsersSupport : ImpersonationWarningSupport,
  DEFINITION <: Definitions.Item,
] {
  this: Logging =>

  def humanReadableName: String

  def createAuthenticationRule(definition: DEFINITION,
                               globalSettings: GlobalSettings): AUTHN_RULE

  def createAuthorizationRule(definition: DEFINITION,
                              groupsLogic: GroupsLogic): AUTHZ_RULE

  def createAuthRule(authnRule: AUTHN_RULE,
                     authzRule: AUTHZ_RULE): AUTH_RULE

  def createAuthorizationRuleWithoutGroups(definition: DEFINITION): AUTHZ_WITHOUT_GROUPS_RULE

  def createAuthRuleWithoutGroups(authnRule: AUTHN_RULE,
                                  authzRule: AUTHZ_WITHOUT_GROUPS_RULE): AUTH_RULE

  def serializeDefinitionId(definition: DEFINITION): String

  class AuthenticationRuleDecoder(definitions: Definitions[DEFINITION],
                                  globalSettings: GlobalSettings) extends RuleBaseDecoderWithoutAssociatedFields[AUTHN_RULE] {
    override protected def decoder: Decoder[RuleDefinition[AUTHN_RULE]] =
      nameAndGroupsSimpleDecoder
        .or(nameAndGroupsExtendedDecoder[AUTHN_RULE])
        .toSyncDecoder
        .emapE { case (name, groupsLogicOpt) =>
          val definitionOpt = definitions.items.find(d => serializeDefinitionId(d) == name)
          (definitionOpt, groupsLogicOpt) match {
            case (Some(_), Some(_)) =>
              Left(RulesLevelCreationError(Message(s"Cannot create ${RuleName[AUTHN_RULE].name.show}, because there are superfluous groups settings. Remove the groups settings, or use ${RuleName[AUTHZ_RULE].name.show} or ${RuleName[AUTH_RULE].name.show} rule, if group settings are required.")))
            case (Some(definition), None) =>
              val rule = createAuthenticationRule(definition, globalSettings)
              Right(RuleDefinition.create(rule))
            case (None, _) =>
              Left(cannotFindDefinition(name))
          }
        }
        .decoder
  }

  class AuthorizationRuleDecoder(definitions: Definitions[DEFINITION]) extends RuleBaseDecoderWithoutAssociatedFields[AUTHZ_RULE] {
    override protected def decoder: Decoder[RuleDefinition[AUTHZ_RULE]] =
      nameAndGroupsSimpleDecoder
        .or(nameAndGroupsExtendedDecoder[AUTHZ_RULE])
        .toSyncDecoder
        .emapE { case (name, groupsLogicOpt) =>
          val definitionOpt = definitions.items.find(d => serializeDefinitionId(d) == name)
          (definitionOpt, groupsLogicOpt) match {
            case (Some(definition), Some(groupsLogic)) =>
              val rule = createAuthorizationRule(definition, groupsLogic)
              Right(RuleDefinition.create[AUTHZ_RULE](rule))
            case (Some(_), None) =>
              Left(RulesLevelCreationError(Message(s"Cannot create ${RuleName[AUTHZ_RULE].name.show} - missing groups logic (https://github.com/beshu-tech/readonlyrest-docs/blob/master/details/authorization-rules-details.md#checking-groups-logic)")))
            case (None, _) =>
              Left(cannotFindDefinition(name))
          }
        }
        .decoder
  }

  class AuthRuleDecoder(definitions: Definitions[DEFINITION],
                        globalSettings: GlobalSettings) extends RuleBaseDecoderWithoutAssociatedFields[AUTH_RULE] {
    override protected def decoder: Decoder[RuleDefinition[AUTH_RULE]] =
      nameAndGroupsSimpleDecoder
        .or(nameAndGroupsExtendedDecoder[AUTH_RULE])
        .toSyncDecoder
        .emapE { case (name, groupsLogicOpt) =>
          val definitionOpt = definitions.items.find(d => serializeDefinitionId(d) == name)
          (definitionOpt, groupsLogicOpt) match {
            case (Some(definition), Some(groupsLogic)) =>
              val authentication = createAuthenticationRule(definition, globalSettings)
              val authorization = createAuthorizationRule(definition, groupsLogic)
              val rule = createAuthRule(authentication, authorization)
              Right(RuleDefinition.create(rule))
            case (Some(definition), None) =>
              logger.warn(
                s"""Missing groups logic settings in ${RuleName[AUTH_RULE].name.show} rule.
                   |For old configs, ROR treats this as `groups_any_of: ["*"]`.
                   |This syntax is deprecated. Add groups logic (https://github.com/beshu-tech/readonlyrest-docs/blob/master/details/authorization-rules-details.md#checking-groups-logic),
                   |or use ${RuleName[AUTHN_RULE].name.show} if you only need authentication.
                   |""".stripMargin
              )
              val authentication = createAuthenticationRule(definition, globalSettings)
              val authorization = createAuthorizationRuleWithoutGroups(definition)
              val rule = createAuthRuleWithoutGroups(authentication, authorization)
              Right(RuleDefinition.create(rule))
            case (None, _) =>
              Left(cannotFindDefinition(name))
          }
        }
        .decoder
  }

  private def nameAndGroupsSimpleDecoder: Decoder[(String, Option[GroupsLogic])] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .map(_.value)
      .map((_, None))

  private def nameAndGroupsExtendedDecoder[RULE <: Rule : RuleName]: Decoder[(String, Option[GroupsLogic])] =
    Decoder
      .instance { c =>
        for {
          definitionName <- c.downField("name").as[NonEmptyString].map(_.value)
          groupsLogicDecodingResult <- GroupsLogicDecoder.decoder[RULE].apply(c)
        } yield (definitionName, groupsLogicDecodingResult)
      }
      .toSyncDecoder
      .emapE {
        case (name, groupsLogicDecodingResult) =>
          groupsLogicDecodingResult match {
            case GroupsLogicDecodingResult.Success(groupsLogic) =>
              Right((name, Some(groupsLogic)))
            case GroupsLogicDecodingResult.GroupsLogicNotDefined(_) =>
              Right((name, None))
            case GroupsLogicDecodingResult.MultipleGroupsLogicsDefined(_, fields) =>
              val fieldsStr = fields.map(f => s"'$f'").mkString(" or ")
              Left(RulesLevelCreationError(Message(
                s"Please specify either $fieldsStr for $humanReadableName authorization rule '$name'"
              )))
          }
      }
      .decoder

  private def cannotFindDefinition(name: String) =
    RulesLevelCreationError(Message(s"Cannot find $humanReadableName definition with name: $name"))

}
