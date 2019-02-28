package tech.beshu.ror.acl.blocks.definitions.ldap

import java.nio.charset.Charset

import com.google.common.hash.Hashing
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import tech.beshu.ror.acl.blocks.definitions.ldap.CacheableLdapAuthenticationServiceDecorator.HashedUserCredentials
import tech.beshu.ror.acl.domain
import tech.beshu.ror.acl.domain.{Group, User}
import tech.beshu.ror.acl.utils.{CacheableAction, CacheableActionWithKeyMapping}

import scala.concurrent.duration.FiniteDuration
import scala.language.higherKinds

class CacheableLdapAuthenticationServiceDecorator(underlying: LdapAuthenticationService,
                                                  ttl: FiniteDuration Refined Positive)
  extends LdapAuthenticationService {

  private val cacheableAuthentication =
    new CacheableActionWithKeyMapping[(User.Id, domain.Secret), HashedUserCredentials, Boolean](ttl, authenticateAction, hashCredential)
  private val cacheableLdapUserService = new CacheableLdapUserServiceDecorator(underlying, ttl)

  override val id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
    cacheableLdapUserService.ldapUserBy(userId)

  override def authenticate(user: User.Id, secret: domain.Secret): Task[Boolean] =
    cacheableAuthentication.call((user, secret))

  private def hashCredential(value: (User.Id, domain.Secret)) = {
    val (user, secret) = value
    HashedUserCredentials(user, Hashing.sha256.hashString(secret.value, Charset.defaultCharset).toString)
  }

  private def authenticateAction(value: (User.Id, domain.Secret)) = {
    val (userId, secret) = value
    underlying.authenticate(userId, secret)
  }
}

object CacheableLdapAuthenticationServiceDecorator {
  private[CacheableLdapAuthenticationServiceDecorator] final case class HashedUserCredentials(user: User.Id,
                                                                                              hashedCredentials: String)
}

class CacheableLdapAuthorizationServiceDecorator(underlying: LdapAuthorizationService,
                                                 ttl: FiniteDuration Refined Positive)
  extends LdapAuthorizationService {

  private val cacheableGroupsOf = new CacheableAction[User.Id, Set[Group]](ttl, underlying.groupsOf)
  private val cacheableLdapUserService = new CacheableLdapUserServiceDecorator(underlying, ttl)

  override val id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
    cacheableLdapUserService.ldapUserBy(userId)

  override def groupsOf(id: User.Id): Task[Set[domain.Group]] =
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

  override def authenticate(user: User.Id, secret: domain.Secret): Task[Boolean] =
    cacheableLdapAuthenticationService.authenticate(user, secret)

  override def groupsOf(id: User.Id): Task[Set[domain.Group]] =
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
