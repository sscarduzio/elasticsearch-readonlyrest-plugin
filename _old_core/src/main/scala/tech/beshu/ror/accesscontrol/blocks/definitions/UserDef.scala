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
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.accesscontrol.domain.{Group, User}
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions.Item
import tech.beshu.ror.accesscontrol.show.logs.userIdShow
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

final case class UserDef(id: UserDef#Id,
                         groups: UniqueNonEmptyList[Group],
                         authenticationRule: AuthenticationRule) extends Item {
  override type Id = User.Id
  override implicit val show: Show[User.Id] = userIdShow
}
