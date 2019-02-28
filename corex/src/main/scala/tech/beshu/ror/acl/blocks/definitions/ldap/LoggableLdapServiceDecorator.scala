package tech.beshu.ror.acl.blocks.definitions.ldap

import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.domain
import tech.beshu.ror.acl.domain.User
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.utils.TaskOps._

import scala.util.Success

class LoggableLdapAuthenticationServiceDecorator(underlying: LdapAuthenticationService)
  extends LdapAuthenticationService
    with Logging {

  private val loggableLdapUserService = new LoggableLdapUserServiceDecorator(underlying)

  override val id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
    loggableLdapUserService.ldapUserBy(userId)

  override def authenticate(user: User.Id, secret: domain.Secret): Task[Boolean] = {
    logger.debug(s"Trying to authenticate user [${user.show}] with LDAP [${id.show}]")
    underlying
      .authenticate(user, secret)
      .andThen { case Success(authenticationResult) =>
        logger.debug(s"User [${user.show}] ${if (authenticationResult) "" else "not"} authenticated by LDAP [${id.show}]")
      }
  }
}

class LoggableLdapAuthorizationServiceDecorator(underlying: LdapAuthorizationService)
  extends LdapAuthorizationService
    with Logging {

  private val loggableLdapUserService = new LoggableLdapUserServiceDecorator(underlying)

  override val id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
    loggableLdapUserService.ldapUserBy(userId)

  override def groupsOf(userId: User.Id): Task[Set[domain.Group]] = {
    logger.debug(s"Trying to fetch user [id=${userId.show}] groups from LDAP [${id.show}]")
    underlying
      .groupsOf(userId)
      .andThen { case Success(groups) =>
        logger.debug(s"LDAP [${id.show}] returned for user [${userId.show}] following groups: [${groups.map(_.show).mkString(",")}]")
      }
  }
}

class LoggableLdapServiceDecorator(underlying: LdapAuthService)
  extends LdapAuthService {

  private val loggableLdapAuthenticationService = new LoggableLdapAuthenticationServiceDecorator(underlying)
  private val loggableLdapAuthorizationService = new LoggableLdapAuthorizationServiceDecorator(underlying)

  override val id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
    loggableLdapAuthenticationService.ldapUserBy(userId)

  override def authenticate(userId: User.Id, secret: domain.Secret): Task[Boolean] =
    loggableLdapAuthenticationService.authenticate(userId, secret)

  override def groupsOf(userId: User.Id): Task[Set[domain.Group]] =
    loggableLdapAuthorizationService.groupsOf(userId)
}

private class LoggableLdapUserServiceDecorator(underlying: LdapUserService)
  extends LdapUserService
    with Logging {

  override val id: LdapService.Name = underlying.id

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] = {
    logger.debug(s"Trying to fetch user with identifier [${userId.show}] from LDAP [${id.show}]")
    underlying
      .ldapUserBy(userId)
      .andThen { case Success(ldapUser) =>
        ldapUser match {
          case Some(user) => logger.debug(s"User with identifier [${userId.show}] found [dn = ${user.dn.show}]")
          case None => logger.debug(s"User with  identifier [${userId.show}] not found")
        }
      }
  }
}