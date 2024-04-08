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

import com.google.common.hash.Hashing
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.CacheableLdapAuthenticationServiceDecorator.HashedUserCredentials
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.{Group, GroupIdLike, User}
import tech.beshu.ror.accesscontrol.utils.{CacheableAction, CacheableActionWithKeyMapping}
import tech.beshu.ror.utils.uniquelist.UniqueList

import java.nio.charset.Charset
import scala.concurrent.duration.FiniteDuration

class CacheableLdapAuthenticationServiceDecorator(underlying: LdapAuthenticationService,
                                                  ttl: FiniteDuration Refined Positive)
  extends LdapAuthenticationService {

  private val cacheableAuthentication =
    new CacheableActionWithKeyMapping[(User.Id, domain.PlainTextSecret), HashedUserCredentials, Boolean](
      ttl = ttl,
      action = {
        case ((u, p), c) => authenticateAction((u, p))(c)
      },
      keyMap = hashCredential
    )

  override def authenticate(user: User.Id, secret: domain.PlainTextSecret)(implicit requestId: RequestId): Task[Boolean] =
    cacheableAuthentication.call((user, secret), serviceTimeout)

  private def hashCredential(value: (User.Id, domain.PlainTextSecret)) = {
    val (user, secret) = value
    HashedUserCredentials(user, Hashing.sha256.hashString(secret.value.value, Charset.defaultCharset).toString)
  }

  private def authenticateAction(value: (User.Id, domain.PlainTextSecret))(implicit requestId: RequestId) = {
    val (userId, secret) = value
    underlying.authenticate(userId, secret)
  }

  override def id: LdapService.Name = underlying.id

  override def ldapUsersService: LdapUsersService = underlying.ldapUsersService

  override def serviceTimeout: Refined[FiniteDuration, Positive] = underlying.serviceTimeout

}

object CacheableLdapAuthenticationServiceDecorator {

  def create(ldapAuthenticationService: LdapAuthenticationService,
             ttl: Option[FiniteDuration Refined Positive]): LdapAuthenticationService = {
    ttl match {
      case Some(ttlValue) => new CacheableLdapAuthenticationServiceDecorator(ldapAuthenticationService, ttlValue)
      case None => ldapAuthenticationService
    }
  }

  private[ldap] final case class HashedUserCredentials(user: User.Id,
                                                       hashedCredentials: String)
}

class CacheableLdapAuthorizationServiceDecorator(underlying: LdapAuthorizationService,
                                                 ttl: FiniteDuration Refined Positive)
  extends LdapAuthorizationService {

  private val cacheableGroupsOf = new CacheableAction[User.Id, UniqueList[Group]](
    ttl = ttl,
    action = (userId, requestId) => underlying.groupsOf(userId)(requestId)
  )

  override def groupsOf(id: User.Id)(implicit requestId: RequestId): Task[UniqueList[Group]] =
    cacheableGroupsOf.call(id, serviceTimeout)

  override def id: LdapService.Name = underlying.id

  override def ldapUsersService: LdapUsersService = underlying.ldapUsersService

  override def serviceTimeout: Refined[FiniteDuration, Positive] = underlying.serviceTimeout
}

class CacheableLdapAuthorizationServiceWithGroupsFilteringDecorator(underlying: LdapAuthorizationServiceWithGroupsFiltering,
                                                                    ttl: FiniteDuration Refined Positive)
  extends LdapAuthorizationServiceWithGroupsFiltering {

  private val cacheableGroupsOf = new CacheableAction[(User.Id, Set[GroupIdLike]), UniqueList[Group]](
    ttl = ttl,
    action = {
      case ((id, groupIds), requestId) => underlying.groupsOf(id, groupIds)(requestId)
    }
  )

  override def groupsOf(id: User.Id, filteringGroupIds: Set[GroupIdLike])(implicit requestId: RequestId): Task[UniqueList[Group]] =
    cacheableGroupsOf.call((id, filteringGroupIds), serviceTimeout)

  override def id: LdapService.Name = underlying.id

  override def ldapUsersService: LdapUsersService = underlying.ldapUsersService

  override def serviceTimeout: Refined[FiniteDuration, Positive] = underlying.serviceTimeout
}

object CacheableLdapAuthorizationServiceWithGroupsFilteringDecorator {

  def create(ldapAuthorizationServiceWithGroupsFiltering: LdapAuthorizationServiceWithGroupsFiltering,
             ttl: Option[FiniteDuration Refined Positive]): LdapAuthorizationServiceWithGroupsFiltering = {
    ttl match {
      case Some(ttlValue) =>
        new CacheableLdapAuthorizationServiceWithGroupsFilteringDecorator(ldapAuthorizationServiceWithGroupsFiltering, ttlValue)
      case None =>
        ldapAuthorizationServiceWithGroupsFiltering
    }
  }
}

class CacheableLdapUsersServiceDecorator(underlying: LdapUsersService,
                                         ttl: FiniteDuration Refined Positive)
  extends LdapUsersService {

  private val cacheableLdapUserById = new CacheableAction[User.Id, Option[LdapUser]](ttl, (u, c) => underlying.ldapUserBy(u)(c))

  override def id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id)(implicit requestId: RequestId): Task[Option[LdapUser]] =
    cacheableLdapUserById.call(userId, serviceTimeout)

  override def serviceTimeout: Refined[FiniteDuration, Positive] = underlying.serviceTimeout
}
object CacheableLdapUsersServiceDecorator {

  def create(ldapUsersService: LdapUsersService,
             ttl: Option[FiniteDuration Refined Positive]): LdapUsersService = {
    ttl match {
      case Some(ttlValue) => new CacheableLdapUsersServiceDecorator(ldapUsersService, ttlValue)
      case None => ldapUsersService
    }
  }
}

class CacheableLdapServiceDecorator(val underlying: LdapAuthService,
                                    ttl: FiniteDuration Refined Positive)
  extends LdapAuthService {

  private val cacheableLdapAuthenticationService = new CacheableLdapAuthenticationServiceDecorator(underlying, ttl)
  private val cacheableLdapAuthorizationService = new CacheableLdapAuthorizationServiceWithGroupsFilteringDecorator(
    underlying, ttl
  )

  override def authenticate(user: User.Id, secret: domain.PlainTextSecret)(implicit requestId: RequestId): Task[Boolean] =
    cacheableLdapAuthenticationService.authenticate(user, secret)

  override def groupsOf(id: User.Id, filteringGroupIds: Set[GroupIdLike])(implicit requestId: RequestId): Task[UniqueList[Group]] =
    cacheableLdapAuthorizationService.groupsOf(id, filteringGroupIds)

  override def id: LdapService.Name = underlying.id

  override val ldapUsersService: LdapUsersService = new CacheableLdapUsersServiceDecorator(underlying.ldapUsersService, ttl)

  override def serviceTimeout: Refined[FiniteDuration, Positive] = underlying.serviceTimeout

}
object CacheableLdapServiceDecorator {

  def create(ldapUsersService: LdapAuthService,
             ttl: Option[FiniteDuration Refined Positive]): LdapAuthService = {
    ttl match {
      case Some(ttlValue) => new CacheableLdapServiceDecorator(ldapUsersService, ttlValue)
      case None => ldapUsersService
    }
  }
}
