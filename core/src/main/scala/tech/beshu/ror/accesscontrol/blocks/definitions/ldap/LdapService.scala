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
import tech.beshu.ror.accesscontrol.domain.{Group, PlainTextSecret, User}
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions.Item

sealed trait LdapService extends Item {
  override type Id = Name
  def id: Id

  override implicit def show: Show[Name] = Name.nameShow
}

object LdapService {
  final case class Name(value: NonEmptyString)
  object Name {
    implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals
    implicit val nameShow: Show[Name] = Show.show(_.value.value)
  }
}

trait LdapUserService extends LdapService {
  def ldapUserBy(userId: User.Id): Task[Option[LdapUser]]
}

trait LdapAuthenticationService extends LdapUserService {
  def authenticate(user: User.Id, secret: PlainTextSecret): Task[Boolean]
}

trait LdapAuthorizationService extends LdapUserService {
  def groupsOf(id: User.Id): Task[Set[Group]]
}

trait LdapAuthService extends LdapAuthenticationService with LdapAuthorizationService

class ComposedLdapAuthService(override val id: LdapService#Id,
                              ldapAuthenticationService: LdapAuthenticationService,
                              ldapAuthorizationService: LdapAuthorizationService)
  extends LdapAuthService {

  def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
    ldapAuthenticationService.ldapUserBy(userId)

  override def authenticate(user: User.Id, secret: PlainTextSecret): Task[Boolean] =
    ldapAuthenticationService.authenticate(user, secret)

  override def groupsOf(id: User.Id): Task[Set[Group]] =
    ldapAuthorizationService.groupsOf(id)
}


final case class LdapUser(id: User.Id, dn: Dn)
final case class Dn(value: NonEmptyString)

