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
package tech.beshu.ror.accesscontrol.blocks.definitions.ldap

import cats.{Eq, Show}
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions.Item
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.uniquelist.UniqueList

sealed trait LdapService extends Item {
  override type Id = Name
  override def idShow: Show[Name] = Show.show(_.value.value)
}

object LdapService {
  final case class Name(value: NonEmptyString)
  object Name {
    implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals
  }
}

trait LdapUsersService extends LdapService {
  def ldapUserBy(userId: User.Id)(implicit requestId: RequestId): Task[Option[LdapUser]]

  def serviceTimeout: PositiveFiniteDuration
}

trait LdapAuthenticationService extends LdapService {
  def ldapUsersService: LdapUsersService

  def authenticate(user: User.Id, secret: PlainTextSecret)(implicit requestId: RequestId): Task[Boolean]

  def serviceTimeout: PositiveFiniteDuration
}

sealed trait LdapAuthorizationService extends LdapService {
  def ldapUsersService: LdapUsersService

  def serviceTimeout: PositiveFiniteDuration
}
object LdapAuthorizationService {
  trait WithoutGroupsFiltering extends LdapAuthorizationService {
    def groupsOf(id: User.Id)(implicit requestId: RequestId): Task[UniqueList[Group]]
  }

  trait WithGroupsFiltering extends LdapAuthorizationService {
    def groupsOf(id: User.Id, filteringGroupIds: Set[GroupIdLike])
                (implicit requestId: RequestId): Task[UniqueList[Group]]
  }
}

class ComposedLdapAuthService private(override val id: LdapService#Id,
                                      val ldapAuthenticationService: LdapAuthenticationService,
                                      val ldapAuthorizationService: LdapAuthorizationService)
  extends LdapService

object ComposedLdapAuthService {
  def create(ldapUsersService: LdapUsersService,
             ldapAuthenticationService: LdapAuthenticationService,
             ldapAuthorizationService: LdapAuthorizationService): Either[String, ComposedLdapAuthService] = {
    Either.cond(
      test = allIdEqual(ldapUsersService, ldapAuthenticationService, ldapAuthorizationService),
      right = new ComposedLdapAuthService(
        ldapUsersService.id,
        ldapAuthenticationService,
        ldapAuthorizationService
      ),
      left = s"You cannot create ComposedLdapAuthService from services with different IDs: [${ldapUsersService.id.show}, ${ldapAuthenticationService.id.show}, ${ldapAuthorizationService.id.show}]"
    )
  }

  private def allIdEqual(ldapServices: LdapService*) = {
    ldapServices.map(_.id).distinct.size == 1
  }

}

final case class LdapUser(id: User.Id, dn: Dn, confirmed: Boolean)
final case class Dn(value: NonEmptyString)

