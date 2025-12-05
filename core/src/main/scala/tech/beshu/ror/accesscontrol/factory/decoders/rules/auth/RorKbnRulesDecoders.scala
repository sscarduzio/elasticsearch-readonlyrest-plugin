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
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{RorKbnAuthRule, RorKbnAuthenticationRule, RorKbnAuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupIdPattern
import tech.beshu.ror.accesscontrol.domain.{GroupIds, GroupsLogic}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.RorKbnDefinitionsDecoder.*
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.RorKbnRulesDecodersHelper.*
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups.GroupsLogicDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups.GroupsLogicRepresentationDecoder.GroupsLogicDecodingResult
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RefinedUtils.nes
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class RorKbnAuthenticationRuleDecoder(rorKbnDefinitions: Definitions[RorKbnDef],
                                      globalSettings: GlobalSettings)
  extends RuleBaseDecoderWithoutAssociatedFields[RorKbnAuthenticationRule] with Logging {

  override protected def decoder: Decoder[RuleDefinition[RorKbnAuthenticationRule]] = {
    nameAndGroupsSimpleDecoder
      .or(nameAndGroupsExtendedDecoder[RorKbnAuthenticationRule])
      .toSyncDecoder
      .emapE { case (name, groupsLogicOpt) =>
        val foundKbnDef = rorKbnDefinitions.items.find(_.id === name)
        (foundKbnDef, groupsLogicOpt) match {
          case (Some(_), Some(_)) =>
            Left(RulesLevelCreationError(Message(s"Cannot create ${RorKbnAuthenticationRule.Name.name.show}, because there are superfluous groups settings. Remove the groups settings, or use ${RorKbnAuthorizationRule.Name.name.show} or ${RorKbnAuthRule.Name.name.show} rule, if group settings are required.")))
          case (Some(rorKbnDef), None) =>
            val settings = RorKbnAuthenticationRule.Settings(rorKbnDef)
            val rule = new RorKbnAuthenticationRule(settings, globalSettings.userIdCaseSensitivity)
            Right(RuleDefinition.create(rule))
          case (None, _) =>
            Left(cannotFindRorKibanaDefinition(name))
        }
      }
      .decoder
  }
}

class RorKbnAuthorizationRuleDecoder(rorKbnDefinitions: Definitions[RorKbnDef])
  extends RuleBaseDecoderWithoutAssociatedFields[RorKbnAuthorizationRule] with Logging {

  override protected def decoder: Decoder[RuleDefinition[RorKbnAuthorizationRule]] = {
    nameAndGroupsSimpleDecoder
      .or(nameAndGroupsExtendedDecoder[RorKbnAuthorizationRule])
      .toSyncDecoder
      .emapE { case (name, groupsLogicOpt) =>
        val foundKbnDef = rorKbnDefinitions.items.find(_.id === name)
        (foundKbnDef, groupsLogicOpt) match {
          case (Some(rorKbnDef), Some(groupsLogic)) =>
            val settings = RorKbnAuthorizationRule.Settings(rorKbnDef, groupsLogic)
            val rule = new RorKbnAuthorizationRule(settings)
            Right(RuleDefinition.create[RorKbnAuthorizationRule](rule))
          case (Some(_), None) =>
            Left(RulesLevelCreationError(Message(s"Cannot create ${RorKbnAuthorizationRule.Name.name.show} - missing groups logic (https://github.com/beshu-tech/readonlyrest-docs/blob/master/details/authorization-rules-details.md#checking-groups-logic)")))
          case (None, _) =>
            Left(cannotFindRorKibanaDefinition(name))
        }
      }
      .decoder
  }
}

class RorKbnAuthRuleDecoder(rorKbnDefinitions: Definitions[RorKbnDef],
                            globalSettings: GlobalSettings)
  extends RuleBaseDecoderWithoutAssociatedFields[RorKbnAuthRule] with Logging {

  override protected def decoder: Decoder[RuleDefinition[RorKbnAuthRule]] = {
    nameAndGroupsSimpleDecoder
      .or(nameAndGroupsExtendedDecoder[RorKbnAuthRule])
      .toSyncDecoder
      .emapE { case (name, groupsLogicOpt) =>
        val foundKbnDef = rorKbnDefinitions.items.find(_.id === name)
        (foundKbnDef, groupsLogicOpt) match {
          case (Some(rorKbnDef), groupsLogicOpt) =>
            val groupsLogic = groupsLogicOpt match {
              case Some(groupsLogic) =>
                groupsLogic
              case None =>
                logger.warn(
                  s"""Missing groups logic settings in ${RorKbnAuthRule.Name.name.show} rule.
                     |For old configs, ROR treats this as `groups_any_of: ["*"]`.
                     |This syntax is deprecated. Add groups logic (https://github.com/beshu-tech/readonlyrest-docs/blob/master/details/authorization-rules-details.md#checking-groups-logic),
                     |or use ${RorKbnAuthenticationRule.Name.name.show} if you only need authentication.
                     |""".stripMargin
                )
                GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupIdPattern.fromNes(nes("*")))))
            }
            val rule = new RorKbnAuthRule(
              authentication = new RorKbnAuthenticationRule(RorKbnAuthenticationRule.Settings(rorKbnDef), globalSettings.userIdCaseSensitivity),
              authorization = new RorKbnAuthorizationRule(RorKbnAuthorizationRule.Settings(rorKbnDef, groupsLogic)),
            )
            Right(RuleDefinition.create(rule))
          case (None, _) =>
            Left(cannotFindRorKibanaDefinition(name))
        }
      }
      .decoder
  }
}

private object RorKbnRulesDecodersHelper {

  def cannotFindRorKibanaDefinition(name: RorKbnDef.Name) =
    RulesLevelCreationError(Message(s"Cannot find ROR Kibana definition with name: ${name.show}"))

  val nameAndGroupsSimpleDecoder: Decoder[(RorKbnDef.Name, Option[GroupsLogic])] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .map(RorKbnDef.Name.apply)
      .map((_, None))

  def nameAndGroupsExtendedDecoder[T <: Rule](implicit ruleName: RuleName[T]): Decoder[(RorKbnDef.Name, Option[GroupsLogic])] =
    Decoder
      .instance { c =>
        for {
          rorKbnDefName <- c.downField("name").as[RorKbnDef.Name]
          groupsLogicDecodingResult <- GroupsLogicDecoder.decoder[T].apply(c)
        } yield (rorKbnDefName, groupsLogicDecodingResult)
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
                s"Please specify either $fieldsStr for ROR Kibana rule '${name.show}'"
              )))
          }
      }
      .decoder

}
