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
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{RorKbnAuthRule, RorKbnAuthenticationRule, RorKbnAuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.GroupsLogic
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.RorKbnDefinitionsDecoder.*
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups.GroupsLogicDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups.GroupsLogicRepresentationDecoder.GroupsLogicDecodingResult
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.implicits.*

class RorKbnAuthenticationRuleDecoder(rorKbnDefinitions: Definitions[RorKbnDef],
                                      globalSettings: GlobalSettings)
  extends RuleBaseDecoderWithoutAssociatedFields[RorKbnAuthenticationRule] with Logging {

  override protected def decoder: Decoder[RuleDefinition[RorKbnAuthenticationRule]] = {
    RorKbnAuthRuleDecoder.nameAndGroupsSimpleDecoder
      .or(RorKbnAuthRuleDecoder.nameAndGroupsExtendedDecoder)
      .toSyncDecoder
      .emapE { case (name, groupsLogicOpt) =>
        rorKbnDefinitions.items.find(_.id === name) match {
          case Some(rorKbnDef) =>
            groupsLogicOpt match {
              case Some(_) =>
                Left(RulesLevelCreationError(Message(s"Cannot create ${RorKbnAuthenticationRule.Name.name.show}, because there are superfluous groups settings. Remove the groups settings, or use authorization or auth rule, if group settings are required.")))
              case None =>
                val settings = RorKbnAuthenticationRule.Settings(rorKbnDef)
                val rule = new RorKbnAuthenticationRule(settings, globalSettings.userIdCaseSensitivity)
                Right(RuleDefinition.create(rule))
            }
          case None =>
            Left(RulesLevelCreationError(Message(s"Cannot find ROR Kibana definition with name: ${name.show}")))
        }
      }
      .decoder
  }
}

class RorKbnAuthorizationRuleDecoder(rorKbnDefinitions: Definitions[RorKbnDef])
  extends RuleBaseDecoderWithoutAssociatedFields[RorKbnAuthorizationRule] with Logging {

  override protected def decoder: Decoder[RuleDefinition[RorKbnAuthorizationRule]] = {
    RorKbnAuthRuleDecoder.nameAndGroupsSimpleDecoder
      .or(RorKbnAuthRuleDecoder.nameAndGroupsExtendedDecoder)
      .toSyncDecoder
      .emapE { case (name, groupsLogicOpt) =>
        rorKbnDefinitions.items.find(_.id === name) match {
          case Some(rorKbnDef) =>
            groupsLogicOpt match {
              case Some(groupsLogic) =>
                val settings = RorKbnAuthorizationRule.Settings(rorKbnDef, groupsLogic)
                val rule = new RorKbnAuthorizationRule(settings)
                Right(RuleDefinition.create[RorKbnAuthorizationRule](rule))
              case None =>
                Left(RulesLevelCreationError(Message(s"Cannot create RorKbnAuthorizationRule - missing groups settings")))
            }
          case None =>
            Left(RulesLevelCreationError(Message(s"Cannot find ROR Kibana definition with name: ${name.show}")))
        }
      }
      .decoder
  }
}

class RorKbnAuthRuleDecoder(rorKbnDefinitions: Definitions[RorKbnDef],
                            globalSettings: GlobalSettings)
  extends RuleBaseDecoderWithoutAssociatedFields[RorKbnAuthRule | RorKbnAuthenticationRule] with Logging {

  override protected def decoder: Decoder[RuleDefinition[RorKbnAuthRule | RorKbnAuthenticationRule]] = {
    RorKbnAuthRuleDecoder.nameAndGroupsSimpleDecoder
      .or(RorKbnAuthRuleDecoder.nameAndGroupsExtendedDecoder)
      .toSyncDecoder
      .emapE[RorKbnAuthRule | RorKbnAuthenticationRule] { case (name, groupsLogicOpt) =>
        rorKbnDefinitions.items.find(_.id === name) match {
          case Some(rorKbnDef) =>
            val rule: RorKbnAuthRule | RorKbnAuthenticationRule = groupsLogicOpt match {
              case Some(groupsLogic) =>
                val settings = RorKbnAuthRule.Settings(rorKbnDef, groupsLogic)
                val rule = new RorKbnAuthRule(settings, globalSettings.userIdCaseSensitivity)
                rule
              case None =>
                logger.warn(s"There are no group mappings configured for rule ${name.value} of type ${RorKbnAuthRule.Name.name.show}. The rule is therefore interpreted as ${RorKbnAuthenticationRule.Name.name.show}. This syntax is deprecated, please change the rule type to ${RorKbnAuthenticationRule.Name.name.show}.")
                val settings = RorKbnAuthenticationRule.Settings(rorKbnDef)
                val rule = new RorKbnAuthenticationRule(settings, globalSettings.userIdCaseSensitivity)
                rule
            }
            Right(rule)
          case None =>
            Left(RulesLevelCreationError(Message(s"Cannot find ROR Kibana definition with name: ${name.show}")))
        }
      }
      .map(RuleDefinition.create[RorKbnAuthRule | RorKbnAuthenticationRule](_))
      .decoder
  }
}

private object RorKbnAuthRuleDecoder {

  val nameAndGroupsSimpleDecoder: Decoder[(RorKbnDef.Name, Option[GroupsLogic])] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .map(RorKbnDef.Name.apply)
      .map((_, None))

  val nameAndGroupsExtendedDecoder: Decoder[(RorKbnDef.Name, Option[GroupsLogic])] =
    Decoder
      .instance { c =>
        for {
          rorKbnDefName <- c.downField("name").as[RorKbnDef.Name]
          groupsLogicDecodingResult <- GroupsLogicDecoder.decoder[RorKbnAuthRule | RorKbnAuthenticationRule].apply(c)
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
