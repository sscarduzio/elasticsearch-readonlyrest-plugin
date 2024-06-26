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

import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef
import tech.beshu.ror.accesscontrol.blocks.rules.auth.RorKbnAuthRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.RorKbnAuthRule.Groups
import tech.beshu.ror.accesscontrol.domain.{GroupsLogic, PermittedGroupIds}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.RorKbnDefinitionsDecoder._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.utils.CirceOps._

class RorKbnAuthRuleDecoder(rorKbnDefinitions: Definitions[RorKbnDef],
                            globalSettings: GlobalSettings)
  extends RuleBaseDecoderWithoutAssociatedFields[RorKbnAuthRule] {

  override protected def decoder: Decoder[RuleDefinition[RorKbnAuthRule]] = {
    RorKbnAuthRuleDecoder.nameAndGroupsSimpleDecoder
      .or(RorKbnAuthRuleDecoder.nameAndGroupsExtendedDecoder)
      .toSyncDecoder
      .emapE { case (name, groupsLogic) =>
        rorKbnDefinitions.items.find(_.id === name) match {
          case Some(rorKbnDef) => Right(RorKbnAuthRule.Settings(rorKbnDef, groupsLogic))
          case None => Left(RulesLevelCreationError(Message(s"Cannot find ROR Kibana definition with name: ${name.show}")))
        }
      }
      .map { settings =>
        RuleDefinition.create(new RorKbnAuthRule(settings, globalSettings.userIdCaseSensitivity))
      }
      .decoder
  }
}

private object RorKbnAuthRuleDecoder {

  private val nameAndGroupsSimpleDecoder: Decoder[(RorKbnDef.Name, Groups)] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .map(RorKbnDef.Name.apply)
      .map((_, Groups.NotDefined))

  private val nameAndGroupsExtendedDecoder: Decoder[(RorKbnDef.Name, Groups)] =
    Decoder
      .instance { c =>
        for {
          rorKbnDefName <- c.downField("name").as[RorKbnDef.Name]
          groupsOrLogic <- {
            val (cursor, key) = c.downFieldsWithKey("roles", "groups", "groups_or")
            cursor.as[Option[PermittedGroupIds]]
              .map {
                _.map(GroupsLogic.Or.apply).map(Groups.Defined.apply).map((_, key))
              }
          }
          groupsAndLogic <- {
            val (cursor, key) = c.downFieldsWithKey("roles_and", "groups_and")
            cursor.as[Option[PermittedGroupIds]]
              .map {
                _.map(GroupsLogic.And.apply).map(Groups.Defined.apply).map((_, key))
              }
          }
        } yield (rorKbnDefName, groupsOrLogic, groupsAndLogic)
      }
      .toSyncDecoder
      .emapE {
        case (name, Some((_, groupsOrKey)), Some((_, groupsAndKey))) =>
          Left(RulesLevelCreationError(Message(
            s"Please specify either '$groupsOrKey' or '$groupsAndKey' for ROR Kibana authorization rule '${name.value.value}'"
          )))
        case (name, Some((groupsOrLogic, _)), None) =>
          Right((name, groupsOrLogic))
        case (name, None, Some((groupsAndLogic, _))) =>
          Right((name, groupsAndLogic))
        case (name, None, None) =>
          Right((name, Groups.NotDefined: Groups))
      }
      .decoder

}
