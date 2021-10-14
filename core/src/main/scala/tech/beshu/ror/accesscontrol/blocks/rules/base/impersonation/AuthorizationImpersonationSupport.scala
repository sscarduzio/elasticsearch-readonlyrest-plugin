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
package tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation

import cats.Eq
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.mocks.{MocksProvider, NoOpMocksProvider}
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.AuthorizationRule
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.AuthorizationImpersonationSupport.Groups
import tech.beshu.ror.accesscontrol.domain.{Group, User}
import tech.beshu.ror.utils.uniquelist.UniqueList

trait AuthorizationImpersonationSupport {
  this: AuthorizationRule =>

  protected def mocksProvider: MocksProvider

  protected[rules] def mockedGroupsOf(user: User.Id)
                                     (implicit requestId: RequestId,
                                      eq: Eq[User.Id]): Groups
}
object AuthorizationImpersonationSupport {
  sealed trait Groups
  object Groups {
    final case class Present(groups: UniqueList[Group]) extends Groups
    case object CannotCheck extends Groups
  }
}

trait NoAuthorizationImpersonationSupport extends AuthorizationImpersonationSupport {
  this: AuthorizationRule =>

  override protected val mocksProvider: MocksProvider = NoOpMocksProvider

  override final protected[rules] def mockedGroupsOf(user: User.Id)
                                                    (implicit requestId: RequestId,
                                                     eq: Eq[User.Id]): Groups =
    Groups.CannotCheck
}

