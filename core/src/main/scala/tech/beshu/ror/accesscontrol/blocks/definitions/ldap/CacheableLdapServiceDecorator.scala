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
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.CacheableLdapAuthenticationServiceDecorator.HashedUserCredentials
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.{Group, GroupIdLike, User}
import tech.beshu.ror.accesscontrol.utils.{CacheableAction, CacheableActionWithKeyMapping}
import tech.beshu.ror.utils.uniquelist.UniqueList

import java.nio.charset.Charset
import scala.concurrent.duration.FiniteDuration

class CacheableLdapAuthenticationServiceDecorator(underlying: LdapAuthenticationService,
                                                  cacheableLdapUserService: CacheableLdapUserServiceDecorator,
                                                  ttl: FiniteDuration Refined Positive)
  extends LdapAuthenticationService {

  def this(underlying: LdapAuthenticationService,
           ttl: FiniteDuration Refined Positive) = {
    this(underlying, new CacheableLdapUserServiceDecorator(underlying, ttl), ttl)
  }

  private val cacheableAuthentication =
    new CacheableActionWithKeyMapping[(User.Id, domain.PlainTextSecret), HashedUserCredentials, Boolean](
      ttl = ttl,
      action = {
        case ((u, p), c) => authenticateAction((u, p))(c)
      },
      keyMap = hashCredential
    )

  override def id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id)(implicit corr: Corr): Task[Option[LdapUser]] =
    cacheableLdapUserService.ldapUserBy(userId)

  override def authenticate(user: User.Id, secret: domain.PlainTextSecret)(implicit corr: Corr): Task[Boolean] =
    cacheableAuthentication.call((user, secret), serviceTimeout)

  private def hashCredential(value: (User.Id, domain.PlainTextSecret)) = {
    val (user, secret) = value
    HashedUserCredentials(user, Hashing.sha256.hashString(secret.value.value, Charset.defaultCharset).toString)
  }

  private def authenticateAction(value: (User.Id, domain.PlainTextSecret))(implicit corr: Corr) = {
    val (userId, secret) = value
    underlying.authenticate(userId, secret)
  }

  override def serviceTimeout: Refined[FiniteDuration, Positive] = underlying.serviceTimeout
}

object CacheableLdapAuthenticationServiceDecorator {
  private[ldap] final case class HashedUserCredentials(user: User.Id,
                                                       hashedCredentials: String)
}

class CacheableLdapAuthorizationServiceDecorator(underlying: LdapAuthorizationService,
                                                 cacheableLdapUserService: CacheableLdapUserServiceDecorator,
                                                 ttl: FiniteDuration Refined Positive)
  extends LdapAuthorizationService {

  def this(underlying: LdapAuthorizationService,
           ttl: FiniteDuration Refined Positive) = {
    this(underlying, new CacheableLdapUserServiceDecorator(underlying, ttl), ttl)
  }

  private val cacheableGroupsOf = new CacheableAction[(User.Id, Set[GroupIdLike]), UniqueList[Group]](
    ttl = ttl,
    action = {
      case ((id, groupIds), corr) => underlying.groupsOf(id, groupIds)(corr)
    }
  )

  override def id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id)(implicit corr: Corr): Task[Option[LdapUser]] =
    cacheableLdapUserService.ldapUserBy(userId)

  override def groupsOf(id: User.Id, filteringGroupIds: Set[GroupIdLike])(implicit corr: Corr): Task[UniqueList[Group]] =
    cacheableGroupsOf.call((id, filteringGroupIds), serviceTimeout)

  override def serviceTimeout: Refined[FiniteDuration, Positive] = underlying.serviceTimeout
}

class CacheableLdapServiceDecorator(val underlying: LdapAuthService,
                                    ttl: FiniteDuration Refined Positive)
  extends LdapAuthService {

  private val cacheableLdapUserService = new CacheableLdapUserServiceDecorator(underlying, ttl)
  private val cacheableLdapAuthenticationService = new CacheableLdapAuthenticationServiceDecorator(
    underlying, cacheableLdapUserService, ttl
  )
  private val cacheableLdapAuthorizationService = new CacheableLdapAuthorizationServiceDecorator(
    underlying, cacheableLdapUserService, ttl
  )

  override def id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id)(implicit corr: Corr): Task[Option[LdapUser]] =
    cacheableLdapAuthenticationService.ldapUserBy(userId)

  override def authenticate(user: User.Id, secret: domain.PlainTextSecret)(implicit corr: Corr): Task[Boolean] =
    cacheableLdapAuthenticationService.authenticate(user, secret)

  override def groupsOf(id: User.Id, filteringGroupIds: Set[GroupIdLike])(implicit corr: Corr): Task[UniqueList[Group]] =
    cacheableLdapAuthorizationService.groupsOf(id, filteringGroupIds)

  override def serviceTimeout: Refined[FiniteDuration, Positive] = underlying.serviceTimeout
}

private class CacheableLdapUserServiceDecorator(underlying: LdapUserService,
                                                ttl: FiniteDuration Refined Positive)
  extends LdapUserService {

  private val cacheableLdapUserById = new CacheableAction[User.Id, Option[LdapUser]](ttl, (u, c) => underlying.ldapUserBy(u)(c))

  override def id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id)(implicit corr: Corr): Task[Option[LdapUser]] =
    cacheableLdapUserById.call(userId, serviceTimeout)

  override def serviceTimeout: Refined[FiniteDuration, Positive] = underlying.serviceTimeout
}
