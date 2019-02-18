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
package tech.beshu.ror.acl.factory.decoders.rules

import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.acl.domain.Group
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.blocks.Value._
import tech.beshu.ror.acl.blocks.definitions.RorKbnDef
import tech.beshu.ror.acl.blocks.rules.RorKbnAuthRule
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.definitions.{Definitions, RorKbnDefinitionsDecoder}
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.acl.factory.decoders.common._
import RorKbnDefinitionsDecoder._

class RorKbnAuthRuleDecoder(rorKbnDefinitions: Definitions[RorKbnDef])
  extends RuleDecoderWithoutAssociatedFields[RorKbnAuthRule](
    RorKbnAuthRuleDecoder.nameAndGroupsSimpleDecoder
      .or(RorKbnAuthRuleDecoder.nameAndGroupsExtendedDecoder)
      .emapE { case (name, groups) =>
        rorKbnDefinitions.items.find(_.id === name) match {
          case Some(rorKbnDef) => Right((rorKbnDef, groups))
          case None => Left(RulesLevelCreationError(Message(s"Cannot find ROR Kibana definition with name: ${name.show}")))
        }
      }
      .map { case (rorKbnDef, groups) =>
        new RorKbnAuthRule(RorKbnAuthRule.Settings(rorKbnDef, groups))
      }
  )

private object RorKbnAuthRuleDecoder {

  private implicit val groupsSetDecoder: Decoder[Set[Value[Group]]] = DecoderHelpers.decodeStringLikeOrSet[Value[Group]]

  private val nameAndGroupsSimpleDecoder: Decoder[(RorKbnDef.Name, Set[Group])] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .map(RorKbnDef.Name.apply)
      .map((_, Set.empty))

  private val nameAndGroupsExtendedDecoder: Decoder[(RorKbnDef.Name, Set[Group])] =
    Decoder.instance { c =>
      for {
        rorKbnDefName <- c.downField("name").as[RorKbnDef.Name]
        groups <- c.downField("roles").as[Option[Set[Group]]]
      } yield (rorKbnDefName, groups.getOrElse(Set.empty))
    }

}
