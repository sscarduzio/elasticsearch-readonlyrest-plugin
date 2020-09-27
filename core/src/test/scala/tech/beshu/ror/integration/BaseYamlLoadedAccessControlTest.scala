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

import java.time.Clock

import cats.implicits._
import tech.beshu.ror.accesscontrol.AccessControl
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory
import tech.beshu.ror.configuration.{RawRorConfig, RorIndexNameConfiguration}
import tech.beshu.ror.mocks.{MockHttpClientsFactory, MockLdapConnectionPoolProvider}
import tech.beshu.ror.providers._
import tech.beshu.ror.utils.TestsPropertiesProvider
import tech.beshu.ror.utils.TestsUtils.BlockContextAssertion
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.configuration.loader.RorConfigurationIndex

trait BaseYamlLoadedAccessControlTest extends BlockContextAssertion {

  protected def configYaml: String

  protected implicit def envVarsProvider: EnvVarsProvider = OsEnvVarsProvider

  protected implicit def propertiesProvider: TestsPropertiesProvider = TestsPropertiesProvider.default

  private val factory = {
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    new RawRorConfigBasedCoreFactory
  }
  protected val ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider = MockLdapConnectionPoolProvider

  lazy val acl: AccessControl = {
    val aclEngineT = for {
      config <- RawRorConfig
        .fromString(configYaml)
        .map(_.fold(err => throw new IllegalStateException(err.show), identity))
      core <- factory
        .createCoreFrom(
          config,
          RorConfigurationIndex(IndexName.fromUnsafeString(".readonlyrest")),
          MockHttpClientsFactory,
          ldapConnectionPoolProvider
        )
        .map(_.fold(err => throw new IllegalStateException(s"Cannot create ACL: $err"), identity))
    } yield core.aclEngine
    aclEngineT.runSyncUnsafe()
  }
}
