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

import java.time.Clock

import com.github.blemale.scaffeine.{Cache, Scaffeine}
import monix.execution.atomic.Atomic
import monix.execution.{Cancelable, Scheduler}
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, ExternalAuthorizationService}
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.{ExternalAuthenticationServiceMock, ExternalAuthorizationServiceMock, LdapServiceMock}

import scala.concurrent.duration._
import scala.language.postfixOps

class MutableMocksProviderWithCachePerRequest(initial: MocksProvider)
                                             (implicit scheduler: Scheduler,
                                              clock: Clock)
  extends MocksProvider {

  def this()
          (implicit scheduler: Scheduler,
           clock: Clock) =
    this(NoOpMocksProvider)

  private val currentMockProvider = Atomic(CurrentMocksProviderConfiguration(initial, None))

  private lazy val cache: Cache[RequestId, MocksProvider] =
    Scaffeine()
      .expireAfterWrite(1 minute)
      .executor(scheduler)
      .build()

  def update(mocksProvider: MocksProvider, ttl: Option[FiniteDuration]): Unit = {
    currentMockProvider.transform { currentMockProviderConfig =>
      currentMockProviderConfig.scheduledInvalidateHandler.foreach(_.cancel())
      CurrentMocksProviderConfiguration(
        mocksProvider,
        ttl.map(scheduler.scheduleOnce(_)(invalidate()))
      )
    }
  }

  def invalidate(): Unit = {
    update(NoOpMocksProvider, None)
  }

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

  private sealed case class CurrentMocksProviderConfiguration(mocksProvider: MocksProvider,
                                                              scheduledInvalidateHandler: Option[Cancelable])
}
