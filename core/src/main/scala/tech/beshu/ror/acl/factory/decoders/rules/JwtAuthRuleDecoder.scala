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
import tech.beshu.ror.acl.blocks.definitions.JwtDef
import tech.beshu.ror.acl.blocks.rules.JwtAuthRule
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.blocks.values.Variable
import tech.beshu.ror.acl.domain.Group
import tech.beshu.ror.acl.factory.decoders.common._
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions
import tech.beshu.ror.acl.factory.decoders.definitions.JwtDefinitionsDecoder._
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.utils.EnvVarsProvider

class JwtAuthRuleDecoder(jwtDefinitions: Definitions[JwtDef])
                        (implicit provider: EnvVarsProvider) extends RuleDecoderWithoutAssociatedFields[JwtAuthRule](
  JwtAuthRuleDecoder.nameAndGroupsSimpleDecoder
    .or(JwtAuthRuleDecoder.nameAndGroupsExtendedDecoder)
    .toSyncDecoder
    .emapE { case (name, groups) =>
      jwtDefinitions.items.find(_.id === name) match {
        case Some(jwtDef) => Right((jwtDef, groups))
        case None => Left(RulesLevelCreationError(Message(s"Cannot find JWT definition with name: ${name.show}")))
      }
    }
    .map { case (jwtDef, groups) =>
      new JwtAuthRule(JwtAuthRule.Settings(jwtDef, groups))
    }
    .decoder
)

private object JwtAuthRuleDecoder {

  private implicit def groupsSetDecoder(implicit provider: EnvVarsProvider): Decoder[Set[Variable[Group]]] =
    DecoderHelpers.decodeStringLikeOrSet[Variable[Group]]

  private val nameAndGroupsSimpleDecoder: Decoder[(JwtDef.Name, Set[Group])] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .map(JwtDef.Name.apply)
      .map((_, Set.empty))

  private val nameAndGroupsExtendedDecoder: Decoder[(JwtDef.Name, Set[Group])] =
    Decoder.instance { c =>
      for {
        jwtDefName <- c.downField("name").as[JwtDef.Name]
        groups <- c.downFields("roles", "groups").as[Option[Set[Group]]]
      } yield (jwtDefName, groups.getOrElse(Set.empty))
    }

}