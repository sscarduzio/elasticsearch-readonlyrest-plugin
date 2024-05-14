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
package tech.beshu.ror.accesscontrol.blocks.mocks

import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import monix.execution.Scheduler
import monix.execution.atomic.Atomic
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, ExternalAuthorizationService}
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.{ExternalAuthenticationServiceMock, ExternalAuthorizationServiceMock, LdapServiceMock}

import scala.language.postfixOps
import java.time.{Duration => JavaDuration}

class MutableMocksProviderWithCachePerRequest(initial: AuthServicesMocks)
                                             (implicit scheduler: Scheduler)
  extends MocksProvider {

  private val currentMockProvider = Atomic(CurrentMocksProviderConfiguration(SimpleMocksProvider(initial)))

  private lazy val cache: Cache[RequestId, MocksProvider] =
    Caffeine.newBuilder()
      .expireAfterWrite(JavaDuration.ofMinutes(1))
      .executor(scheduler)
      .build()

  def update(mocks: AuthServicesMocks): Unit = {
    currentMockProvider.transform { currentMockProviderConfig =>
      CurrentMocksProviderConfiguration(SimpleMocksProvider(mocks))
    }
  }

  def invalidate(): Unit = {
    update(AuthServicesMocks.empty)
  }

  def currentMocks: AuthServicesMocks = currentMockProvider.get().mocksProvider.mocks

  override def ldapServiceWith(id: LdapService.Name)
                              (implicit context: RequestId): Option[LdapServiceMock] = {
    getMockProviderByContext(context).ldapServiceWith(id)
  }

  override def externalAuthenticationServiceWith(id: ExternalAuthenticationService.Name)
                                                (implicit context: RequestId): Option[ExternalAuthenticationServiceMock] = {
    getMockProviderByContext(context).externalAuthenticationServiceWith(id)
  }

  override def externalAuthorizationServiceWith(id: ExternalAuthorizationService.Name)
                                               (implicit context: RequestId): Option[ExternalAuthorizationServiceMock] = {
    getMockProviderByContext(context).externalAuthorizationServiceWith(id)
  }

  private def getMockProviderByContext(context: RequestId) =
    cache.get(context, _ => currentMockProvider.get().mocksProvider)

  private sealed case class CurrentMocksProviderConfiguration(mocksProvider: SimpleMocksProvider)
}
