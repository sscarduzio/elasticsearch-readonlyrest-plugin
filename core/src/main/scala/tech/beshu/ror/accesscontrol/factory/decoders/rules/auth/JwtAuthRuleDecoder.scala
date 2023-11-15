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
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef
import tech.beshu.ror.accesscontrol.blocks.rules.auth.JwtAuthRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.JwtAuthRule.Groups
import tech.beshu.ror.accesscontrol.domain.{GroupsLogic, PermittedGroups}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.JwtDefinitionsDecoder._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.utils.CirceOps._

class JwtAuthRuleDecoder(jwtDefinitions: Definitions[JwtDef],
                         globalSettings: GlobalSettings)
  extends RuleBaseDecoderWithoutAssociatedFields[JwtAuthRule] {

  override protected def decoder: Decoder[RuleDefinition[JwtAuthRule]] = {
    JwtAuthRuleDecoder.nameAndGroupsSimpleDecoder
      .or(JwtAuthRuleDecoder.nameAndGroupsExtendedDecoder)
      .toSyncDecoder
      .emapE { case (name, groupsLogic) =>
        jwtDefinitions.items.find(_.id === name) match {
          case Some(jwtDef) => Right(JwtAuthRule.Settings(jwtDef, groupsLogic))
          case None => Left(RulesLevelCreationError(Message(s"Cannot find JWT definition with name: ${name.show}")))
        }
      }
      .map { settings =>
        RuleDefinition.create(new JwtAuthRule(settings, globalSettings.userIdCaseSensitivity))
      }
      .decoder
  }
}

private object JwtAuthRuleDecoder {

  private val nameAndGroupsSimpleDecoder: Decoder[(JwtDef.Name, Groups)] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .map(JwtDef.Name.apply)
      .map((_, Groups.NotDefined))

  private val nameAndGroupsExtendedDecoder: Decoder[(JwtDef.Name, Groups)] =
    Decoder
      .instance { c =>
        for {
          rorKbnDefName <- c.downField("name").as[JwtDef.Name]
          groupsOrLogic <- {
            val (cursor, key) = c.downFieldsWithKey("roles", "groups", "groups_or")
            cursor.as[Option[PermittedGroups]]
              .map {
                _.map(GroupsLogic.Or).map(Groups.Defined).map((_, key))
              }
          }
          groupsAndLogic <- {
            val (cursor, key) = c.downFieldsWithKey("roles_and", "groups_and")
            cursor.as[Option[PermittedGroups]]
              .map {
                _.map(GroupsLogic.And).map(Groups.Defined).map((_, key))
              }
          }
        } yield (rorKbnDefName, groupsOrLogic, groupsAndLogic)
      }
      .toSyncDecoder
      .emapE {
        case (name, Some((_, groupsOrKey)), Some((_, groupsAndKey))) =>
          Left(RulesLevelCreationError(Message(
            s"Please specify either '$groupsOrKey' or '$groupsAndKey' for JWT authorization rule '${name.value.value}'"
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