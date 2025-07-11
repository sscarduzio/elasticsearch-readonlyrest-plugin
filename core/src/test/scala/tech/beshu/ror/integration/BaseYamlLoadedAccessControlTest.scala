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
package tech.beshu.ror.integration

import cats.implicits.*
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.accesscontrol.AccessControlList
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.blocks.mocks.{MocksProvider, NoOpMocksProvider}
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorSettingsIndex}
import tech.beshu.ror.accesscontrol.factory.{HttpClientsFactory, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.configuration.RawRorSettings
import tech.beshu.ror.mocks.{MockHttpClientsFactory, MockLdapConnectionPoolProvider}
import tech.beshu.ror.providers.*
import tech.beshu.ror.utils.TestsPropertiesProvider
import tech.beshu.ror.utils.TestsUtils.{BlockContextAssertion, defaultEsVersionForTests, unsafeNes}

trait BaseYamlLoadedAccessControlTest extends BlockContextAssertion {

  protected def configYaml: String

  protected implicit def envVarsProvider: EnvVarsProvider = OsEnvVarsProvider

  protected implicit def propertiesProvider: TestsPropertiesProvider = TestsPropertiesProvider.default

  private implicit val systemContext: SystemContext = new Environment(
    envVarsProvider = envVarsProvider,
    propertiesProvider = propertiesProvider
  )
  private val factory = new RawRorConfigBasedCoreFactory(defaultEsVersionForTests)
  protected val ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider = MockLdapConnectionPoolProvider
  protected val httpClientsFactory: HttpClientsFactory = MockHttpClientsFactory
  protected val mockProvider: MocksProvider = NoOpMocksProvider

  lazy val acl: AccessControlList = {
    val aclEngineT = for {
      config <- RawRorSettings
        .fromString(configYaml)
        .map(_.fold(err => throw new IllegalStateException(err.show), identity))
      core <- factory
        .createCoreFrom(
          config,
          RorSettingsIndex(IndexName.Full(".readonlyrest")),
          httpClientsFactory,
          ldapConnectionPoolProvider,
          mockProvider
        )
        .map(_.fold(err => throw new IllegalStateException(s"Cannot create ACL: $err"), identity))
    } yield core.accessControl
    aclEngineT.runSyncUnsafe()
  }
}
