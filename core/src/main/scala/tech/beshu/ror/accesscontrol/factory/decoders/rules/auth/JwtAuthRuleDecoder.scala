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

import io.circe.Decoder
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{JwtAuthRule, JwtAuthenticationRule, JwtAuthorizationRule, JwtPseudoAuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.GroupsLogic
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.JwtDefinitionsDecoder.*
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.JwtAuthRuleHelper.*
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups.GroupsLogicDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups.GroupsLogicRepresentationDecoder.GroupsLogicDecodingResult
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.implicits.*

class JwtAuthenticationRuleDecoder(jwtDefinitions: Definitions[JwtDef],
                                   globalSettings: GlobalSettings)
  extends RuleBaseDecoderWithoutAssociatedFields[JwtAuthenticationRule] with Logging {

  override protected def decoder: Decoder[RuleDefinition[JwtAuthenticationRule]] = {
    nameAndGroupsSimpleDecoder
      .or(nameAndGroupsExtendedDecoder[JwtAuthenticationRule])
      .toSyncDecoder
      .emapE { case (name, groupsLogicOpt) =>
        val foundJwtDef = jwtDefinitions.items.find(_.id === name)
        (foundJwtDef, groupsLogicOpt) match {
          case (Some(_), Some(_)) =>
            Left(RulesLevelCreationError(Message(s"Cannot create ${JwtAuthenticationRule.Name.name.show}, because there are superfluous groups settings. Remove the groups settings, or use ${JwtAuthorizationRule.Name.name.show} or ${JwtAuthRule.Name.name.show} rule, if group settings are required.")))
          case (Some(jwtDef), None) =>
            val settings = JwtAuthenticationRule.Settings(jwtDef)
            val rule = new JwtAuthenticationRule(settings, globalSettings.userIdCaseSensitivity)
            Right(RuleDefinition.create(rule))
          case (None, _) =>
            Left(cannotFindJwtDefinition(name))
        }
      }
      .decoder
  }
}

class JwtAuthorizationRuleDecoder(jwtDefinitions: Definitions[JwtDef])
  extends RuleBaseDecoderWithoutAssociatedFields[JwtAuthorizationRule] with Logging {

  override protected def decoder: Decoder[RuleDefinition[JwtAuthorizationRule]] = {
    nameAndGroupsSimpleDecoder
      .or(nameAndGroupsExtendedDecoder[JwtAuthorizationRule])
      .toSyncDecoder
      .emapE { case (name, groupsLogicOpt) =>
        val foundJwtDef = jwtDefinitions.items.find(_.id === name)
        (foundJwtDef, groupsLogicOpt) match {
          case (Some(jwtDef), Some(groupsLogic)) =>
            val settings = JwtAuthorizationRule.Settings(jwtDef, groupsLogic)
            val rule = new JwtAuthorizationRule(settings)
            Right(RuleDefinition.create[JwtAuthorizationRule](rule))
          case (Some(_), None) =>
            Left(RulesLevelCreationError(Message(s"Cannot create ${JwtAuthorizationRule.Name.name.show} - missing groups logic (https://github.com/beshu-tech/readonlyrest-docs/blob/master/details/authorization-rules-details.md#checking-groups-logic)")))
          case (None, _) =>
            Left(cannotFindJwtDefinition(name))
        }
      }
      .decoder
  }
}

class JwtAuthRuleDecoder(jwtDefinitions: Definitions[JwtDef],
                         globalSettings: GlobalSettings)
  extends RuleBaseDecoderWithoutAssociatedFields[JwtAuthRule] with Logging {

  override protected def decoder: Decoder[RuleDefinition[JwtAuthRule]] = {
    nameAndGroupsSimpleDecoder
      .or(nameAndGroupsExtendedDecoder[JwtAuthRule])
      .toSyncDecoder
      .emapE { case (name, groupsLogicOpt) =>
        val foundJwtDef = jwtDefinitions.items.find(_.id === name)
        foundJwtDef match {
          case Some(jwtDef) =>
            val authentication = new JwtAuthenticationRule(JwtAuthenticationRule.Settings(jwtDef), globalSettings.userIdCaseSensitivity)
            val authorization: JwtAuthorizationRule | JwtPseudoAuthorizationRule = groupsLogicOpt match {
              case Some(groupsLogic) =>
                new JwtAuthorizationRule(JwtAuthorizationRule.Settings(jwtDef, groupsLogic))
              case None =>
                logger.warn(
                  s"""Missing groups logic settings in ${JwtAuthRule.Name.name.show} rule.
                     |For old configs, ROR treats this as `groups_any_of: ["*"]`.
                     |This syntax is deprecated. Add groups logic (https://github.com/beshu-tech/readonlyrest-docs/blob/master/details/authorization-rules-details.md#checking-groups-logic),
                     |or use ${JwtAuthenticationRule.Name.name.show} if you only need authentication.
                     |""".stripMargin
                )
                new JwtPseudoAuthorizationRule(JwtPseudoAuthorizationRule.Settings(jwtDef))
            }
            val rule = new JwtAuthRule(authentication, authorization)
            Right(RuleDefinition.create(rule))
          case None =>
            Left(cannotFindJwtDefinition(name))
        }
      }
      .decoder
  }
}

private object JwtAuthRuleHelper {

  def cannotFindJwtDefinition(name: JwtDef.Name) =
    RulesLevelCreationError(Message(s"Cannot find JWT definition with name: ${name.show}"))

  val nameAndGroupsSimpleDecoder: Decoder[(JwtDef.Name, Option[GroupsLogic])] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .map(JwtDef.Name.apply)
      .map((_, None))

  def nameAndGroupsExtendedDecoder[T <: Rule](implicit ruleName: RuleName[T]): Decoder[(JwtDef.Name, Option[GroupsLogic])] =
    Decoder
      .instance { c =>
        for {
          jwtDefName <- c.downField("name").as[JwtDef.Name]
          groupsLogicDecodingResult <- GroupsLogicDecoder.decoder[T].apply(c)
        } yield (jwtDefName, groupsLogicDecodingResult)
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
                s"Please specify either $fieldsStr for JWT authorization rule '${name.show}'"
              )))
          }
      }
      .decoder

}