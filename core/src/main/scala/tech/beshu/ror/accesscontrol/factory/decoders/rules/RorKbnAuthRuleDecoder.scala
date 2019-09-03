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
package tech.beshu.ror.accesscontrol.factory.decoders.rules

import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef
import tech.beshu.ror.accesscontrol.blocks.rules.RorKbnAuthRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.domain.Group
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.RorKbnDefinitionsDecoder._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import DecoderHelpers.decodeUniqueList
import tech.beshu.ror.utils.uniquelist.UniqueList

class RorKbnAuthRuleDecoder(rorKbnDefinitions: Definitions[RorKbnDef])
  extends RuleDecoderWithoutAssociatedFields[RorKbnAuthRule](
    RorKbnAuthRuleDecoder.nameAndGroupsSimpleDecoder
      .or(RorKbnAuthRuleDecoder.nameAndGroupsExtendedDecoder)
      .toSyncDecoder
      .emapE { case (name, groups) =>
        rorKbnDefinitions.items.find(_.id === name) match {
          case Some(rorKbnDef) => Right((rorKbnDef, groups))
          case None => Left(RulesLevelCreationError(Message(s"Cannot find ROR Kibana definition with name: ${name.show}")))
        }
      }
      .map { case (rorKbnDef, groups) =>
        new RorKbnAuthRule(RorKbnAuthRule.Settings(rorKbnDef, groups))
      }
      .decoder
  )

private object RorKbnAuthRuleDecoder {

  private implicit val groupsSetDecoder: Decoder[UniqueList[RuntimeSingleResolvableVariable[Group]]] =
    DecoderHelpers.decodeStringLikeOrUniqueList[RuntimeSingleResolvableVariable[Group]]

  private val nameAndGroupsSimpleDecoder: Decoder[(RorKbnDef.Name, UniqueList[Group])] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .map(RorKbnDef.Name.apply)
      .map((_, UniqueList.empty))

  private val nameAndGroupsExtendedDecoder: Decoder[(RorKbnDef.Name, UniqueList[Group])] =
    Decoder.instance { c =>
      for {
        rorKbnDefName <- c.downField("name").as[RorKbnDef.Name]
        groups <- c.downFields("roles", "groups").as[Option[UniqueList[Group]]]
      } yield (rorKbnDefName, groups.getOrElse(UniqueList.empty))
    }

}
