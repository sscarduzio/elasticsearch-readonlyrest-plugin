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

import cats.implicits.toShow
import monix.catnap._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.CircuitBreakerConfig
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.{Group, GroupIdLike, User}
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.uniquelist.UniqueList

class CircuitBreakerLdapAuthenticationServiceDecorator(underlying: LdapAuthenticationService,
                                                       override val circuitBreakerConfig: CircuitBreakerConfig)
  extends LdapAuthenticationService
    with LdapCircuitBreaker {

  override def authenticate(user: User.Id, secret: domain.PlainTextSecret)(implicit requestId: RequestId): Task[Boolean] = {
    protect(
      underlying.authenticate(user, secret)
    )
  }

  override def id: LdapService.Name = underlying.id

  override def ldapUsersService: LdapUsersService = underlying.ldapUsersService

  override def serviceTimeout: PositiveFiniteDuration = underlying.serviceTimeout
}

class CircuitBreakerLdapAuthorizationServiceWithGroupsFilteringDecorator(underlying: LdapAuthorizationServiceWithGroupsFiltering,
                                                                         override val circuitBreakerConfig: CircuitBreakerConfig)
  extends LdapAuthorizationServiceWithGroupsFiltering
    with LdapCircuitBreaker {

  override def groupsOf(id: User.Id, filteringGroupIds: Set[GroupIdLike])
                       (implicit requestId: RequestId): Task[UniqueList[Group]] = {
    protect(
      underlying.groupsOf(id, filteringGroupIds)
    )
  }

  override def id: LdapService.Name = underlying.id

  override def serviceTimeout: PositiveFiniteDuration = underlying.serviceTimeout

  override def ldapUsersService: LdapUsersService = underlying.ldapUsersService
}

class CircuitBreakerLdapAuthServiceDecorator(underlying: LdapAuthService,
                                             override val circuitBreakerConfig: CircuitBreakerConfig)
  extends LdapAuthService
    with LdapCircuitBreaker {

  override def authenticate(user: User.Id, secret: domain.PlainTextSecret)(implicit requestId: RequestId): Task[Boolean] = {
    protect(
      underlying.authenticate(user, secret)
    )
  }

  override def groupsOf(id: User.Id, filteringGroupIds: Set[GroupIdLike])(implicit requestId: RequestId): Task[UniqueList[Group]] = {
    protect(
      underlying.groupsOf(id, filteringGroupIds)
    )
  }

  override def id: LdapService.Name = underlying.id

  override def serviceTimeout: PositiveFiniteDuration = underlying.serviceTimeout

  override def ldapUsersService: LdapUsersService = underlying.ldapUsersService
}

class CircuitBreakerLdapUsersServiceDecorator(underlying: LdapUsersService,
                                              override val circuitBreakerConfig: CircuitBreakerConfig)
  extends LdapUsersService
    with LdapCircuitBreaker {
  override def ldapUserBy(userId: User.Id)(implicit requestId: RequestId): Task[Option[LdapUser]] = {
    protect(
      underlying.ldapUserBy(userId)
    )
  }

  override def id: LdapService.Name = underlying.id

  override def serviceTimeout: PositiveFiniteDuration = underlying.serviceTimeout
}

trait LdapCircuitBreaker extends Logging {
  this: LdapService =>

  protected def circuitBreakerConfig: CircuitBreakerConfig

  private val circuitBreaker = {
    val CircuitBreakerConfig(maxFailures, resetDuration) = circuitBreakerConfig
    CircuitBreaker[Task]
      .unsafe(
        maxFailures = maxFailures.value,
        resetTimeout = resetDuration.value,
        onRejected = Task {
          logger.debug(s"LDAP ${id.show} circuit breaker rejected task (Open or HalfOpen state)")
        },
        onClosed = Task {
          logger.debug(s"LDAP ${id.show} circuit breaker is accepting tasks again (switched to Close state)")
        },
        onHalfOpen = Task {
          logger.debug(s"LDAP ${id.show} circuit breaker accepted one task for testing (switched to HalfOpen state)")
        },
        onOpen = Task {
          logger.debug(s"LDAP ${id.show} circuit breaker rejected task (switched to Open state)")
        }
      )
  }

  protected def protect[A](task: => Task[A]): Task[A] = {
    circuitBreaker.protect(Task.defer(task))
  }
}