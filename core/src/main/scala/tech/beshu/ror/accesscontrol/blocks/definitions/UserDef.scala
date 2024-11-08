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

import cats.Show
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.GroupMappings.Advanced.Mapping
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithGroupsMapping.Auth
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.{GroupMappings, Mode}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthRule, AuthenticationRule, AuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.{Group, GroupIdLike, UserIdPatterns}
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions.Item
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.util.UUID

final case class UserDef private(override val id: UUID,
                                 usernames: UserIdPatterns,
                                 mode: Mode)
  extends Item {

  override type Id = UUID // artificial ID (won't be used)
  override val idShow: Show[UUID] = Show.show(_.toString)

  def localGroups: UniqueNonEmptyList[Group] =
    mode match {
      case Mode.WithoutGroupsMapping(_, localGroups) => localGroups
      case Mode.WithGroupsMapping(_, GroupMappings.Simple(localGroups)) => localGroups
      case Mode.WithGroupsMapping(_, GroupMappings.Advanced(mappings)) =>
        UniqueNonEmptyList.unsafeFrom(mappings.map(_.local))
    }
}

object UserDef {

  def apply(usernames: UserIdPatterns,
            mode: Mode): UserDef =
    new UserDef(UUID.randomUUID(), usernames, mode)

  sealed trait Mode
  object Mode {
    final case class WithoutGroupsMapping(auth: AuthenticationRule,
                                          localGroups: UniqueNonEmptyList[Group])
      extends Mode

    final case class WithGroupsMapping(auth: Auth,
                                       groupMappings: GroupMappings)
      extends Mode
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

  sealed trait GroupMappings
  object GroupMappings {
    final case class Simple(localGroups: UniqueNonEmptyList[Group]) extends GroupMappings
    final case class Advanced(mappings: UniqueNonEmptyList[Mapping]) extends GroupMappings
    object Advanced {
      final case class Mapping(local: Group, externalGroupPatterns: UniqueNonEmptyList[GroupIdLike]) {
        val externalGroupIdPatternsMatcher: PatternsMatcher[GroupIdLike] = PatternsMatcher.create(externalGroupPatterns.toSet)
      }
    }
  }
}