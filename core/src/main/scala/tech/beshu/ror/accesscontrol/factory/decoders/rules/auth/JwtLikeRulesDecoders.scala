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
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.ImpersonationWarning.ImpersonationWarningSupport
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthRule, AuthenticationRule, AuthorizationRule, RuleName}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.VariableUsage
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupIdPattern
import tech.beshu.ror.accesscontrol.domain.{GroupIds, GroupsLogic}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common.nonEmptyStringDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups.GroupsLogicDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups.GroupsLogicRepresentationDecoder.GroupsLogicDecodingResult
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RefinedUtils.nes
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

// Common decoder for JWT rules and ROR KBN rules. They are very similar, and their decoding logic is mostly the same.
trait JwtLikeRulesDecoders[
  DEF <: Definitions.Item,
  AUTHN_DEF <: DEF,
  AUTHZ_DEF <: DEF,
  AUTH_DEF <: AUTHN_DEF & AUTHZ_DEF,
  AUTHN_RULE <: AuthenticationRule : RuleName : VariableUsage : ImpersonationWarningSupport,
  AUTHZ_RULE <: AuthorizationRule : RuleName : VariableUsage : ImpersonationWarningSupport,
  AUTH_RULE <: AuthRule : RuleName : VariableUsage : ImpersonationWarningSupport,
] {
  this: RequestIdAwareLogging =>

  protected def ruleTypePrefix: String

  protected def docsUrl: String

  protected def createAuthenticationRule(definition: AUTHN_DEF,
                                         globalSettings: GlobalSettings): AUTHN_RULE

  protected def createAuthorizationRule(definition: AUTHZ_DEF,
                                        groupsLogic: GroupsLogic): AUTHZ_RULE

  protected def createAuthRule(authnRule: AUTHN_RULE,
                               authzRule: AUTHZ_RULE): AUTH_RULE

  protected def serializeDefinitionId(definition: DEF): String

  class AuthenticationRuleDecoder(allDefs: List[DEF],
                                  authnDefs: List[AUTHN_DEF],
                                  globalSettings: GlobalSettings) extends RuleBaseDecoderWithoutAssociatedFields[AUTHN_RULE] {
    override protected def decoder: Decoder[RuleDefinition[AUTHN_RULE]] =
      nameAndGroupsSimpleDecoder
        .or(nameAndGroupsExtendedDecoder[AUTHN_RULE])
        .toSyncDecoder
        .emapE { case (name, groupsLogicOpt) =>
          val definitionE = findDefinition[AUTHN_RULE, AUTHN_DEF](authnDefs, allDefs.diff(authnDefs), name)
          (definitionE, groupsLogicOpt) match {
            case (Right(_), Some(_)) =>
              Left(RulesLevelCreationError(Message(s"Cannot create ${RuleName[AUTHN_RULE].name.show}, because there are superfluous groups settings. Remove the groups settings, or use ${RuleName[AUTHZ_RULE].name.show} or ${RuleName[AUTH_RULE].name.show} rule, if group settings are required.")))
            case (Right(definition), None) =>
              val rule = createAuthenticationRule(definition, globalSettings)
              Right(RuleDefinition.create(rule))
            case (Left(error), _) =>
              Left(error)
          }
        }
        .decoder
  }

  class AuthorizationRuleDecoder(allDefs: List[DEF],
                                 authzDefs: List[AUTHZ_DEF]) extends RuleBaseDecoderWithoutAssociatedFields[AUTHZ_RULE] {
    override protected def decoder: Decoder[RuleDefinition[AUTHZ_RULE]] =
      nameAndGroupsSimpleDecoder
        .or(nameAndGroupsExtendedDecoder[AUTHZ_RULE])
        .toSyncDecoder
        .emapE { case (name, groupsLogicOpt) =>
          val definitionE = findDefinition[AUTHZ_RULE, AUTHZ_DEF](authzDefs, allDefs.diff(authzDefs), name)
          (definitionE, groupsLogicOpt) match {
            case (Right(definition), Some(groupsLogic)) =>
              val rule = createAuthorizationRule(definition, groupsLogic)
              Right(RuleDefinition.create[AUTHZ_RULE](rule))
            case (Right(_), None) =>
              Left(RulesLevelCreationError(Message(s"Cannot create ${RuleName[AUTHZ_RULE].name.show} - missing groups logic (https://github.com/beshu-tech/readonlyrest-docs/blob/master/details/authorization-rules-details.md#checking-groups-logic)")))
            case (Left(error), _) =>
              Left(error)
          }
        }
        .decoder
  }

  class AuthRuleDecoder(allDefs: List[DEF],
                        authDefs: List[AUTH_DEF],
                        globalSettings: GlobalSettings) extends RuleBaseDecoderWithoutAssociatedFields[AUTH_RULE] {
    override protected def decoder: Decoder[RuleDefinition[AUTH_RULE]] =
      nameAndGroupsSimpleDecoder
        .or(nameAndGroupsExtendedDecoder[AUTH_RULE])
        .toSyncDecoder
        .emapE { case (name, groupsLogicOpt) =>
          val definitionE = findDefinition[AUTH_RULE, AUTH_DEF](authDefs, allDefs.diff(authDefs), name)
          (definitionE, groupsLogicOpt) match {
            case (Right(definition), Some(groupsLogic)) =>
              val authentication = createAuthenticationRule(definition, globalSettings)
              val authorization = createAuthorizationRule(definition, groupsLogic)
              val rule = createAuthRule(authentication, authorization)
              Right(RuleDefinition.create(rule))
            case (Right(definition), None) =>
              noRequestIdLogger.warn(
                s"""Missing groups logic settings in ${RuleName[AUTH_RULE].name.show} rule.
                   |For old configs, ROR treats this as `groups_any_of: ["*"]`.
                   |This syntax is deprecated. Add groups logic (https://github.com/beshu-tech/readonlyrest-docs/blob/master/details/authorization-rules-details.md#checking-groups-logic),
                   |or use ${RuleName[AUTHN_RULE].name.show} if you only need authentication.
                   |""".stripMargin
              )
              val authentication = createAuthenticationRule(definition, globalSettings)
              val groupsLogic = GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupIdPattern.fromNes(nes("*")))))
              val authorization = createAuthorizationRule(definition, groupsLogic)
              val rule = createAuthRule(authentication, authorization)
              Right(RuleDefinition.create(rule))
            case (Left(error), _) =>
              Left(error)
          }
        }
        .decoder
  }

  private def findDefinition[T <: Rule, CURRENT_DEF <: DEF](definitionsOfCurrentType: List[CURRENT_DEF],
                                                            definitionsOfOtherType: List[DEF],
                                                            name: String)
                                                           (implicit ruleName: RuleName[T]): Either[RulesLevelCreationError, CURRENT_DEF] = {
    val definitionOfCurrentTypeOpt = findByName(definitionsOfCurrentType, name)
    lazy val definitionOfOtherTypeOpt = findByName(definitionsOfOtherType, name)
    definitionOfCurrentTypeOpt match {
      case Some(definition) =>
        Right(definition)
      case None =>
        val message = definitionOfOtherTypeOpt match {
          case Some(_) =>
            s"The $ruleTypePrefix definition with name $name exists, but cannot be used for ${ruleName.name.show} rule. " +
              s"Please check in the documentation ($docsUrl) how to adjust the $ruleTypePrefix definition to use it for both authentication and authorization"
          case None =>
            s"Cannot find `$ruleTypePrefix` definition with name: $name"
        }
        Left(RulesLevelCreationError(Message(message)))
    }
  }

  private def findByName[T <: DEF](definitions: List[T], name: String): Option[T] = {
    definitions.find(d => serializeDefinitionId(d) == name)
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
                s"Please specify either $fieldsStr for `${RuleName[RULE].name.show}` rule '$name'"
              )))
          }
      }
      .decoder

}
