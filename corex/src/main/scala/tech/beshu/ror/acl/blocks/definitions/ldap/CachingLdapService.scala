package tech.beshu.ror.acl.blocks.definitions.ldap

import com.github.blemale.scaffeine.Scaffeine
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import tech.beshu.ror.acl.domain
import tech.beshu.ror.acl.domain.User
import tech.beshu.ror.acl.utils.TaskOps._

import scala.concurrent.duration.FiniteDuration
import scala.util.Success

class CachingLdapAuthenticationService(underlying: LdapAuthenticationService,
                                       ttl: FiniteDuration Refined Positive)
  extends LdapAuthenticationService {

  private val cachingLdapUserService = new CachingLdapUserService(underlying, ttl)

  override val id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
    cachingLdapUserService.ldapUserBy(userId)

  override def authenticate(user: User.Id, secret: domain.Secret): Task[Boolean] = ???
}

class CachingLdapAuthorizationService(underlying: LdapAuthorizationService,
                                      ttl: FiniteDuration Refined Positive)
  extends LdapAuthorizationService {

  private val cachingLdapUserService = new CachingLdapUserService(underlying, ttl)

  override val id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
    cachingLdapUserService.ldapUserBy(userId)

  override def groupsOf(id: User.Id): Task[Set[domain.Group]] = ???
}

class CachingLdapService(underlying: LdapAuthService,
                         ttl: FiniteDuration Refined Positive)
  extends LdapAuthService {

  private val cachingLdapAuthenticationService = new CachingLdapAuthenticationService(underlying, ttl)
  private val cachingLdapAuthorizationService = new CachingLdapAuthorizationService(underlying, ttl)

  override val id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
    cachingLdapAuthenticationService.ldapUserBy(userId)

  override def authenticate(user: User.Id, secret: domain.Secret): Task[Boolean] =
    cachingLdapAuthenticationService.authenticate(user, secret)

  override def groupsOf(id: User.Id): Task[Set[domain.Group]] =
    cachingLdapAuthorizationService.groupsOf(id)
}

private class CachingLdapUserService(underlying: LdapUserService,
                                     ttl: FiniteDuration Refined Positive)
  extends LdapUserService {

  private val cache = Scaffeine()
    .expireAfterWrite(ttl.value)
    .build[User.Id, Option[LdapUser]]

  override val id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] = {
    cache.getIfPresent(userId) match {
      case Some(cachedLdapUser) =>
        Task.now(cachedLdapUser)
      case None =>
        underlying
          .ldapUserBy(userId)
          .andThen {
            case Success(foundLdapUser) => cache.put(userId, foundLdapUser)
          }
    }
  }

}

object CachingLdapService {

  private[ldap] final case class HashedUserCredentials(user: User.Id, hashedCredentials: String)

}