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

import cats.implicits._
import cats.{Eq, Show}
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.accesscontrol.domain.{Group, GroupIdLike, PlainTextSecret, User}
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions.Item
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.uniquelist.UniqueList

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

trait LdapUsersService extends LdapService {
  def ldapUserBy(userId: User.Id)(implicit requestId: RequestId): Task[Option[LdapUser]]

  def serviceTimeout: PositiveFiniteDuration
}

trait LdapAuthenticationService extends LdapService {
  def ldapUsersService: LdapUsersService

  def authenticate(user: User.Id, secret: PlainTextSecret)(implicit requestId: RequestId): Task[Boolean]

  def serviceTimeout: PositiveFiniteDuration
}

trait LdapAuthorizationService extends LdapService {
  def ldapUsersService: LdapUsersService

  def groupsOf(id: User.Id)(implicit requestId: RequestId): Task[UniqueList[Group]]

  def serviceTimeout: PositiveFiniteDuration
}
object LdapAuthorizationService {

  final class NoOpLdapAuthorizationServiceAdapter(val underlying: LdapAuthorizationServiceWithGroupsFiltering)
    extends LdapAuthorizationService {
    override def id: Name = underlying.id

    override def ldapUsersService: LdapUsersService = underlying.ldapUsersService

    override def groupsOf(id: User.Id)
                         (implicit requestId: RequestId): Task[UniqueList[Group]] = underlying.groupsOf(id, Set.empty)

    override def serviceTimeout: PositiveFiniteDuration = underlying.serviceTimeout
  }
}

trait LdapAuthorizationServiceWithGroupsFiltering extends LdapService {
  def ldapUsersService: LdapUsersService

  def groupsOf(id: User.Id, filteringGroupIds: Set[GroupIdLike])
              (implicit requestId: RequestId): Task[UniqueList[Group]]

  def serviceTimeout: PositiveFiniteDuration
}
object LdapAuthorizationServiceWithGroupsFiltering {

  final class NoOpLdapAuthorizationServiceWithGroupsFilteringAdapter(val underlying: LdapAuthorizationService)
    extends LdapAuthorizationServiceWithGroupsFiltering {
    override def id: Name = underlying.id

    override def ldapUsersService: LdapUsersService = underlying.ldapUsersService

    override def groupsOf(id: User.Id, filteringGroupIds: Set[GroupIdLike])
                         (implicit requestId: RequestId): Task[UniqueList[Group]] = underlying.groupsOf(id)

    override def serviceTimeout: PositiveFiniteDuration = underlying.serviceTimeout
  }
}

class ComposedLdapAuthService private(override val id: LdapService#Id,
                                      val ldapAuthenticationService: LdapAuthenticationService,
                                      val ldapAuthorizationService: LdapAuthorizationServiceWithGroupsFiltering)
  extends LdapService

object ComposedLdapAuthService {
  def create(ldapUsersService: LdapUsersService,
             ldapAuthenticationService: LdapAuthenticationService,
             ldapAuthorizationService: LdapAuthorizationServiceWithGroupsFiltering): Either[String, ComposedLdapAuthService] = {
    for {
      _ <- Either.cond(
        test = allIdEqual(ldapUsersService, ldapAuthenticationService, ldapAuthorizationService),
        right = (),
        left = s"You cannot create ComposedLdapAuthService from services with different IDs: [${ldapUsersService.id.show}, ${ldapAuthenticationService.id.show}, ${ldapAuthorizationService.id.show}]"
      )
    } yield new ComposedLdapAuthService(
      ldapUsersService.id,
      ldapAuthenticationService,
      ldapAuthorizationService
    )
  }

  private def allIdEqual(ldapServices: LdapService*) = {
    ldapServices.map(_.id).distinct.size == 1
  }

}

final case class LdapUser(id: User.Id, dn: Dn, confirmed: Boolean)
final case class Dn(value: NonEmptyString)

