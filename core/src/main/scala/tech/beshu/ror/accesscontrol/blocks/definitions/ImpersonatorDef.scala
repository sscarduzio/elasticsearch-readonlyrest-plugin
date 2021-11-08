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
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.AuthenticationRule
import tech.beshu.ror.accesscontrol.domain.{User, UserIdPatterns}
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions.Item
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

final case class ImpersonatorDef private(id: ImpersonatorDef#Id,
                                         usernames: UserIdPatterns,
                                         authenticationRule: AuthenticationRule,
                                         users: UniqueNonEmptyList[User.Id])
  extends Item {

  override type Id = UUID // artificial ID (won't be used)
  override implicit val show: Show[UUID] = Show.show(_.toString)
}
object ImpersonatorDef {

  def apply(usernames: UserIdPatterns,
            authenticationRule: AuthenticationRule,
            users: UniqueNonEmptyList[User.Id]): ImpersonatorDef =
    new ImpersonatorDef(UUID.randomUUID(), usernames, authenticationRule, users)
}

