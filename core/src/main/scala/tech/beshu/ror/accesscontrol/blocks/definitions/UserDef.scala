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
package tech.beshu.ror.accesscontrol.blocks.definitions

import java.util.UUID

import cats.Show
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithGroupsMapping.Auth
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.{GroupMapping, Mode}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthRule, AuthenticationRule, AuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.{Group, UserIdPatterns}
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions.Item
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

final case class UserDef private(id: UserDef#Id,
                                 usernames: UserIdPatterns,
                                 groupMappings: UniqueNonEmptyList[GroupMapping],
                                 mode: Mode)
  extends Item {

  override type Id = UUID // artificial ID (won't be used)
  override implicit val show: Show[UUID] = Show.show(_.toString)

  def localGroups: UniqueNonEmptyList[Group] = UniqueNonEmptyList.unsafeFromSet {
    groupMappings
      .map {
        case GroupMapping.AnyExternalGroupToLocalGroupMapping(local) => local
        case GroupMapping.LocalGroupToExternalGroupsMapping(local, _) => local
      }
      .toSet
  }
}

object UserDef {

  def apply(usernames: UserIdPatterns,
            groupMappings: UniqueNonEmptyList[GroupMapping],
            mode: Mode): UserDef =
    new UserDef(UUID.randomUUID(), usernames, groupMappings, mode)

  sealed trait Mode
  object Mode {
    final case class WithoutGroupsMapping(auth: AuthenticationRule) extends Mode
    final case class WithGroupsMapping(auth: Auth) extends Mode
    object WithGroupsMapping {
      sealed trait Auth
      object Auth {
        final case class SeparateRules(authenticationRule: AuthenticationRule,
                                       authorizationRule: AuthorizationRule)
          extends Auth
        final case class SingleRule(rule: AuthRule)
          extends Auth
      }
    }
  }

  sealed trait GroupMapping
  object GroupMapping {
    final case class AnyExternalGroupToLocalGroupMapping(local: Group)
      extends GroupMapping
    final case class LocalGroupToExternalGroupsMapping(local: Group,
                                                       externalGroups: UniqueNonEmptyList[Group])
      extends GroupMapping
  }
}