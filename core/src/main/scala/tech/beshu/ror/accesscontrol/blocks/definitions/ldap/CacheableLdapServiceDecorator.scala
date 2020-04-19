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

import java.nio.charset.Charset

import com.google.common.hash.Hashing
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.CacheableLdapAuthenticationServiceDecorator.HashedUserCredentials
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.{Group, User}
import tech.beshu.ror.accesscontrol.utils.{CacheableAction, CacheableActionWithKeyMapping}
import tech.beshu.ror.utils.uniquelist.UniqueList

import scala.concurrent.duration.FiniteDuration

class CacheableLdapAuthenticationServiceDecorator(underlying: LdapAuthenticationService,
                                                  ttl: FiniteDuration Refined Positive)
  extends LdapAuthenticationService {

  private val cacheableAuthentication =
    new CacheableActionWithKeyMapping[(User.Id, domain.PlainTextSecret), HashedUserCredentials, Boolean](ttl, authenticateAction, hashCredential)
  private val cacheableLdapUserService = new CacheableLdapUserServiceDecorator(underlying, ttl)

  override val id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
    cacheableLdapUserService.ldapUserBy(userId)

  override def authenticate(user: User.Id, secret: domain.PlainTextSecret): Task[Boolean] =
    cacheableAuthentication.call((user, secret))

  private def hashCredential(value: (User.Id, domain.PlainTextSecret)) = {
    val (user, secret) = value
    HashedUserCredentials(user, Hashing.sha256.hashString(secret.value.value, Charset.defaultCharset).toString)
  }

  private def authenticateAction(value: (User.Id, domain.PlainTextSecret)) = {
    val (userId, secret) = value
    underlying.authenticate(userId, secret)
  }
}

object CacheableLdapAuthenticationServiceDecorator {
  private[ldap] final case class HashedUserCredentials(user: User.Id,
                                                       hashedCredentials: String)
}

class CacheableLdapAuthorizationServiceDecorator(underlying: LdapAuthorizationService,
                                                 ttl: FiniteDuration Refined Positive)
  extends LdapAuthorizationService {

  private val cacheableGroupsOf = new CacheableAction[User.Id, UniqueList[Group]](ttl, underlying.groupsOf)
  private val cacheableLdapUserService = new CacheableLdapUserServiceDecorator(underlying, ttl)

  override val id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
    cacheableLdapUserService.ldapUserBy(userId)

  override def groupsOf(id: User.Id): Task[UniqueList[domain.Group]] =
    cacheableGroupsOf.call(id)
}

class CacheableLdapServiceDecorator(underlying: LdapAuthService,
                                    ttl: FiniteDuration Refined Positive)
  extends LdapAuthService {

  private val cacheableLdapAuthenticationService = new CacheableLdapAuthenticationServiceDecorator(underlying, ttl)
  private val cacheableLdapAuthorizationService = new CacheableLdapAuthorizationServiceDecorator(underlying, ttl)

  override val id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
    cacheableLdapAuthenticationService.ldapUserBy(userId)

  override def authenticate(user: User.Id, secret: domain.PlainTextSecret): Task[Boolean] =
    cacheableLdapAuthenticationService.authenticate(user, secret)

  override def groupsOf(id: User.Id): Task[UniqueList[domain.Group]] =
    cacheableLdapAuthorizationService.groupsOf(id)
}

private class CacheableLdapUserServiceDecorator(underlying: LdapUserService,
                                                ttl: FiniteDuration Refined Positive)
  extends LdapUserService {

  private val cacheableLdapUserById = new CacheableAction[User.Id, Option[LdapUser]](ttl, underlying.ldapUserBy)

  override val id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
    cacheableLdapUserById.call(userId)

}
