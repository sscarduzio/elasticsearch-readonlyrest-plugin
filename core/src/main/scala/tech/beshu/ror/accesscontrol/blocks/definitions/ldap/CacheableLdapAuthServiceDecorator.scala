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
import monix.eval.Task
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.CacheableLdapAuthenticationServiceDecorator.HashedUserCredentials
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.{Group, GroupIdLike, User}
import tech.beshu.ror.accesscontrol.utils.{CacheableAction, CacheableActionWithKeyMapping}
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.uniquelist.UniqueList

import java.nio.charset.Charset

class CacheableLdapAuthenticationServiceDecorator(val underlying: LdapAuthenticationService,
                                                  val ttl: PositiveFiniteDuration,
                                                  cacheLdapUserServiceAsWell: Boolean)
  extends LdapAuthenticationService {

  private val cacheableAuthentication =
    new CacheableActionWithKeyMapping[(User.Id, domain.PlainTextSecret), HashedUserCredentials, Boolean](
      ttl = ttl,
      action = {
        case ((userId, secret), requestId) => authenticateAction((userId, secret))(requestId)
      },
      keyMap = hashCredential
    )

  override val ldapUsersService: LdapUsersService = CacheableLdapUsersServiceDecorator.create(
    ldapUsersService = underlying.ldapUsersService,
    ttl = Option.when(cacheLdapUserServiceAsWell)(ttl)
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

  override def serviceTimeout: PositiveFiniteDuration = underlying.serviceTimeout
}

object CacheableLdapAuthenticationServiceDecorator {

  def create(ldapAuthenticationService: LdapAuthenticationService,
             ttl: Option[PositiveFiniteDuration]): LdapAuthenticationService = {
    create(ldapAuthenticationService, ttl, cacheLdapUserServiceAsWell = false)
  }

  def createWithCachebaleLdapUsersService(ldapAuthenticationService: LdapAuthenticationService,
                                          ttl: Option[PositiveFiniteDuration]): LdapAuthenticationService = {
    create(ldapAuthenticationService, ttl, cacheLdapUserServiceAsWell = true)
  }

  private def create(ldapAuthenticationService: LdapAuthenticationService,
                     ttl: Option[PositiveFiniteDuration],
                     cacheLdapUserServiceAsWell: Boolean) = {
    ttl match {
      case Some(ttlValue) => new CacheableLdapAuthenticationServiceDecorator(ldapAuthenticationService, ttlValue, cacheLdapUserServiceAsWell)
      case None => ldapAuthenticationService
    }
  }

  private[ldap] final case class HashedUserCredentials(user: User.Id,
                                                       hashedCredentials: String)
}

class CacheableLdapAuthorizationServiceWithGroupsFilteringDecorator(val underlying: LdapAuthorizationServiceWithGroupsFiltering,
                                                                    val ttl: PositiveFiniteDuration,
                                                                    cacheLdapUserServiceAsWell: Boolean)
  extends LdapAuthorizationServiceWithGroupsFiltering {

  private val cacheableGroupsOf = new CacheableAction[(User.Id, Set[GroupIdLike]), UniqueList[Group]](
    ttl = ttl,
    action = {
      case ((id, groupIds), requestId) => underlying.groupsOf(id, groupIds)(requestId)
    }
  )

  override val ldapUsersService: LdapUsersService = CacheableLdapUsersServiceDecorator.create(
    ldapUsersService = underlying.ldapUsersService,
    ttl = Option.when(cacheLdapUserServiceAsWell)(ttl)
  )

  override def groupsOf(id: User.Id, filteringGroupIds: Set[GroupIdLike])(implicit requestId: RequestId): Task[UniqueList[Group]] =
    cacheableGroupsOf.call((id, filteringGroupIds), serviceTimeout)

  override def id: LdapService.Name = underlying.id

  override def serviceTimeout: PositiveFiniteDuration = underlying.serviceTimeout
}

object CacheableLdapAuthorizationServiceWithGroupsFilteringDecorator {

  def create(ldapService: LdapAuthorizationServiceWithGroupsFiltering,
             ttl: Option[PositiveFiniteDuration]): LdapAuthorizationServiceWithGroupsFiltering = {
    create(ldapService, ttl, cacheLdapUserServiceAsWell = false)
  }

  def createWithCachebaleLdapUsersService(ldapService: LdapAuthorizationServiceWithGroupsFiltering,
                                          ttl: Option[PositiveFiniteDuration]): LdapAuthorizationServiceWithGroupsFiltering = {
    create(ldapService, ttl, cacheLdapUserServiceAsWell = true)
  }

  private def create(ldapService: LdapAuthorizationServiceWithGroupsFiltering,
                     ttl: Option[PositiveFiniteDuration],
                     cacheLdapUserServiceAsWell: Boolean) = {
    ttl match {
      case Some(ttlValue) =>
        new CacheableLdapAuthorizationServiceWithGroupsFilteringDecorator(ldapService, ttlValue, cacheLdapUserServiceAsWell)
      case None =>
        ldapService
    }
  }
}

class CacheableLdapUsersServiceDecorator(val underlying: LdapUsersService,
                                         val ttl: PositiveFiniteDuration)
  extends LdapUsersService {

  private val cacheableLdapUserById = new CacheableAction[User.Id, Option[LdapUser]](
    ttl = ttl,
    action = (userId, requestId) => underlying.ldapUserBy(userId)(requestId)
  )

  override def id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id)(implicit requestId: RequestId): Task[Option[LdapUser]] =
    cacheableLdapUserById.call(userId, serviceTimeout)

  override def serviceTimeout: PositiveFiniteDuration = underlying.serviceTimeout
}
object CacheableLdapUsersServiceDecorator {

  def create(ldapUsersService: LdapUsersService,
             ttl: Option[PositiveFiniteDuration]): LdapUsersService = {
    ttl match {
      case Some(ttlValue) => new CacheableLdapUsersServiceDecorator(ldapUsersService, ttlValue)
      case None => ldapUsersService
    }
  }
}
