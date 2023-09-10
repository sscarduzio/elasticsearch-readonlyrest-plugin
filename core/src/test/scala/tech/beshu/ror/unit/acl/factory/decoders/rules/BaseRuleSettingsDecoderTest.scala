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
package tech.beshu.ror.unit.acl.factory.decoders.rules

import cats.data.NonEmptyList
import eu.timepit.refined.auto._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, Inside, Suite}
import tech.beshu.ror.accesscontrol.acl.AccessControlList
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.blocks.mocks.{MocksProvider, NoOpMocksProvider}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.{Core, HttpClientsFactory, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.configuration.EnvironmentConfig
import tech.beshu.ror.mocks.MockHttpClientsFactory
import tech.beshu.ror.providers._
import tech.beshu.ror.utils.TestsUtils._

import scala.reflect.ClassTag

abstract class BaseRuleSettingsDecoderTest[T <: Rule : ClassTag] extends AnyWordSpec with BeforeAndAfterAll with Inside {
  this: Suite =>

  val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

  override protected def afterAll(): Unit = {
    super.afterAll()
    ldapConnectionPoolProvider.close()
  }

  protected implicit def envVarsProvider: EnvVarsProvider = OsEnvVarsProvider

  protected def factory: RawRorConfigBasedCoreFactory = {
    implicit val environmentConfig: EnvironmentConfig = EnvironmentConfig
      .default
      .copy(
        envVarsProvider = envVarsProvider
      )
    new RawRorConfigBasedCoreFactory()
  }

  def assertDecodingSuccess(yaml: String,
                            assertion: T => Unit,
                            aFactory: RawRorConfigBasedCoreFactory = factory,
                            httpClientsFactory: HttpClientsFactory = MockHttpClientsFactory,
                            mocksProvider: MocksProvider = NoOpMocksProvider): Unit = {
    inside(
      aFactory
        .createCoreFrom(
          rorConfigFromUnsafe(yaml),
          RorConfigurationIndex(IndexName.Full(".readonlyrest")),
          httpClientsFactory,
          ldapConnectionPoolProvider,
          mocksProvider
        )
        .runSyncUnsafe()
    ) { case Right(Core(acl: AccessControlList, _)) =>
      val rule = acl.blocks.head.rules.collect { case r: T => r }.headOption
        .getOrElse(throw new IllegalStateException("There was no expected rule in decoding result"))
      rule shouldBe a[T]
      assertion(rule.asInstanceOf[T])
    }
  }

  def assertDecodingFailure(yaml: String,
                            assertion: NonEmptyList[CoreCreationError] => Unit,
                            aFactory: RawRorConfigBasedCoreFactory = factory,
                            httpClientsFactory: HttpClientsFactory = MockHttpClientsFactory,
                            mocksProvider: MocksProvider = NoOpMocksProvider): Unit = {
    inside(
      aFactory
        .createCoreFrom(
          rorConfigFromUnsafe(yaml),
          RorConfigurationIndex(IndexName.Full(".readonlyrest")),
          httpClientsFactory,
          ldapConnectionPoolProvider,
          mocksProvider
        )
        .runSyncUnsafe()
    ) { case Left(error) =>
      assertion(error)
    }
  }
}
