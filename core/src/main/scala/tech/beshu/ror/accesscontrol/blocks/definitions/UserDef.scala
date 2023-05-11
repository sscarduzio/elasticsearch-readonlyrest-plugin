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
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName
import tech.beshu.ror.accesscontrol.domain.{GroupLike, UserIdPatterns}
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions.Item
import tech.beshu.ror.accesscontrol.matchers.{Matcher, MatcherWithWildcardsScalaAdapter}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.util.UUID

final case class UserDef private(id: UserDef#Id,
                                 usernames: UserIdPatterns,
                                 mode: Mode)
  extends Item {

  override type Id = UUID // artificial ID (won't be used)
  override implicit val show: Show[UUID] = Show.show(_.toString)

  def localGroups: UniqueNonEmptyList[GroupName] = UniqueNonEmptyList.unsafeFromIterable {
    mode match {
      case Mode.WithoutGroupsMapping(_, localGroups) => localGroups
      case Mode.WithGroupsMapping(_, GroupMappings.Simple(localGroups)) => localGroups
      case Mode.WithGroupsMapping(_, GroupMappings.Advanced(mappings)) => mappings.unsorted.map(_.local)
    }
  }
}

object UserDef {

  def apply(usernames: UserIdPatterns,
            mode: Mode): UserDef =
    new UserDef(UUID.randomUUID(), usernames, mode)

  sealed trait Mode
  object Mode {
    final case class WithoutGroupsMapping(auth: AuthenticationRule,
                                          localGroups: UniqueNonEmptyList[GroupName])
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
    final case class Simple(localGroups: UniqueNonEmptyList[GroupName]) extends GroupMappings
    final case class Advanced(mappings: UniqueNonEmptyList[Mapping]) extends GroupMappings
    object Advanced {
      final case class Mapping(local: GroupName, externalGroupPatters: UniqueNonEmptyList[GroupLike]) {
        val externalGroupPattersMatcher: Matcher[GroupLike] =
          MatcherWithWildcardsScalaAdapter.create(externalGroupPatters)
      }
    }
  }
}