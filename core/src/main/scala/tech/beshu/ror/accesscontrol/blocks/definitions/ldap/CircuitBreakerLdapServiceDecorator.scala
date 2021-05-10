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

import monix.catnap._
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.definitions.CircuitBreakerConfig
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.utils.uniquelist.UniqueList

class CircuitBreakerLdapAuthenticationServiceDecorator(underlying: LdapAuthenticationService,
                                                       circuitBreakerConfig: CircuitBreakerConfig)
  extends LdapAuthenticationService {

  private val circuitBreaker = {
    val CircuitBreakerConfig(maxFailures, resetDuration) = circuitBreakerConfig
    CircuitBreaker[Task].unsafe(
      maxFailures.value,
      resetDuration.value
    )
  }

  override def authenticate(user: User.Id, secret: domain.PlainTextSecret): Task[Boolean] = {
    circuitBreaker.protect(underlying.authenticate(user, secret))
  }

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] = {
    circuitBreaker.protect(underlying.ldapUserBy(userId))
  }

  override def id: LdapService.Name = underlying.id
}

class CircuitBreakerLdapAuthorizationServiceDecorator(underlying: LdapAuthorizationService,
                                                      circuitBreakerConfig: CircuitBreakerConfig)
  extends LdapAuthorizationService {

  private val circuitBreaker = {
    val CircuitBreakerConfig(maxFailures, resetDuration) = circuitBreakerConfig
    CircuitBreaker[Task].unsafe(
      maxFailures.value,
      resetDuration.value
    )
  }

  override def groupsOf(id: User.Id): Task[UniqueList[domain.Group]] = {
    circuitBreaker.protect(underlying.groupsOf(id))
  }

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] = {
    circuitBreaker.protect(underlying.ldapUserBy(userId))
  }

  override def id: LdapService.Name = underlying.id
}

class CircuitBreakerLdapServiceDecorator(underlying: LdapAuthService,
                                         circuitBreakerConfig: CircuitBreakerConfig)
  extends LdapAuthService {

  private val circuitBreaker = {
    val CircuitBreakerConfig(maxFailures, resetDuration) = circuitBreakerConfig
    CircuitBreaker[Task].unsafe(
      maxFailures.value,
      resetDuration.value
    )
  }

  override def authenticate(user: User.Id, secret: domain.PlainTextSecret): Task[Boolean] = {
    circuitBreaker.protect(underlying.authenticate(user, secret))
  }

  override def groupsOf(id: User.Id): Task[UniqueList[domain.Group]] = {
    circuitBreaker.protect(underlying.groupsOf(id))
  }

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] = {
    circuitBreaker.protect(underlying.ldapUserBy(userId))
  }

  override def id: LdapService.Name = underlying.id
}