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

import com.github.blemale.scaffeine.{Cache, Scaffeine}
import monix.execution.atomic.Atomic
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.LdapServiceMock

import scala.concurrent.duration._
import scala.language.postfixOps

class MutableMocksProviderWithCachePerRequest(initial: MocksProvider)
  extends MocksProvider {

  def this() = this(NoOpMocksProvider)

  private val currentMockProvider = Atomic(initial)

  private val cache: Cache[RequestId, MocksProvider] =
    Scaffeine()
      .expireAfterWrite(1 minute)
      .build()

  def update(mocksProvider: MocksProvider): Unit = {
    currentMockProvider.update(mocksProvider)
  }

  def invalidate(): Unit = {
    currentMockProvider.update(NoOpMocksProvider)
  }

  override def ldapServiceWith(id: LdapService.Name)
                              (implicit context: RequestId): Option[LdapServiceMock] = {
    getMockProviderByContext(context).ldapServiceWith(id)
  }

  private def getMockProviderByContext(context: RequestId) =
    cache.get(context, _ => currentMockProvider.get())
}
