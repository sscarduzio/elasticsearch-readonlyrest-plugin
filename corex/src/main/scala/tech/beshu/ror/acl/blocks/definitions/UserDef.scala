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
package tech.beshu.ror.acl.blocks.definitions

import cats.Show
import cats.data.NonEmptySet
import tech.beshu.ror.acl.domain.{Group, User}
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions.Item
import tech.beshu.ror.acl.show.logs.userIdShow

final case class UserDef(id: UserDef#Id,
                         groups: NonEmptySet[Group],
                         authenticationRule: AuthenticationRule) extends Item {
  override type Id = User.Id
  override implicit val show: Show[User.Id] = userIdShow
}
