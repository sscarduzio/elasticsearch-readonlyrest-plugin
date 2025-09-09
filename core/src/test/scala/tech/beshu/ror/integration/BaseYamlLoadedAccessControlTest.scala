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
import squants.information.Megabytes
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.AccessControlList
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.blocks.mocks.{MocksProvider, NoOpMocksProvider}
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorSettingsIndex}
import tech.beshu.ror.accesscontrol.factory.{HttpClientsFactory, RawRorSettingsBasedCoreFactory}
import tech.beshu.ror.configuration.RawRorSettingsYamlParser
import tech.beshu.ror.mocks.{MockHttpClientsFactory, MockLdapConnectionPoolProvider}
import tech.beshu.ror.providers.*
import tech.beshu.ror.utils.TestsPropertiesProvider
import tech.beshu.ror.utils.TestsUtils.{BlockContextAssertion, defaultEsVersionForTests, unsafeNes}

trait BaseYamlLoadedAccessControlTest extends BlockContextAssertion {

  protected def settingsYaml: String

  protected implicit def envVarsProvider: EnvVarsProvider = OsEnvVarsProvider

  protected implicit def propertiesProvider: TestsPropertiesProvider = TestsPropertiesProvider.default

  private implicit val systemContext: SystemContext = new SystemContext(
    envVarsProvider = envVarsProvider,
    propertiesProvider = propertiesProvider
  )
  private val factory = new RawRorSettingsBasedCoreFactory(defaultEsVersionForTests)
  protected val ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider = MockLdapConnectionPoolProvider
  protected val httpClientsFactory: HttpClientsFactory = MockHttpClientsFactory
  protected val mockProvider: MocksProvider = NoOpMocksProvider

  lazy val acl: AccessControlList = {
    val yamlParser = new RawRorSettingsYamlParser(Megabytes(3))
    val rorSettings = yamlParser.fromString(settingsYaml) match {
      case Right(value) => value
      case Left(error) => throw new IllegalStateException(error.show)
    }
    factory
      .createCoreFrom(
        rorSettings,
        RorSettingsIndex(IndexName.Full(".readonlyrest")),
        httpClientsFactory,
        ldapConnectionPoolProvider,
        mockProvider
      )
      .map(_.fold(err => throw new IllegalStateException(s"Cannot create ACL: $err"), _.accessControl))
      .runSyncUnsafe()
  }
}
