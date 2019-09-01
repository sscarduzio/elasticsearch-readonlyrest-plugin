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
package tech.beshu.ror.unit.acl.factory.decoders

import java.time.Clock

import cats.data.NonEmptyList
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers.{a, _}
import org.scalatest.{Inside, Suite, WordSpec}
import tech.beshu.ror.accesscontrol.acl.Acl
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError
import tech.beshu.ror.accesscontrol.factory.{CoreSettings, HttpClientsFactory, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.mocks.MockHttpClientsFactory
import tech.beshu.ror.providers._
import tech.beshu.ror.unit.utils.TestsPropertiesProvider
import tech.beshu.ror.utils.TestsUtils._

import scala.reflect.ClassTag

abstract class BaseRuleSettingsDecoderTest[T <: Rule : ClassTag] extends WordSpec with Inside {
  this: Suite =>

  protected implicit def envVarsProvider: EnvVarsProvider = OsEnvVarsProvider

  protected def factory(propertiesProvider: TestsPropertiesProvider = TestsPropertiesProvider.default) = {
    implicit val _ = propertiesProvider
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    new RawRorConfigBasedCoreFactory
  }

  def assertDecodingSuccess(yaml: String,
                            assertion: T => Unit,
                            aFactory: RawRorConfigBasedCoreFactory = factory(),
                            httpClientsFactory: HttpClientsFactory = MockHttpClientsFactory): Unit = {
    inside(aFactory.createCoreFrom(rorConfigFrom(yaml), httpClientsFactory).runSyncUnsafe()) { case Right(CoreSettings(acl: Acl, _, _)) =>
      val rule = acl.blocks.head.rules.collect { case r: T => r }.headOption
        .getOrElse(throw new IllegalStateException("There was no expected rule in decoding result"))
      rule shouldBe a[T]
      assertion(rule.asInstanceOf[T])
    }
  }

  def assertDecodingFailure(yaml: String,
                            assertion: NonEmptyList[AclCreationError] => Unit,
                            aFactory: RawRorConfigBasedCoreFactory = factory(),
                            httpClientsFactory: HttpClientsFactory = MockHttpClientsFactory): Unit = {
    inside(aFactory.createCoreFrom(rorConfigFrom(yaml), httpClientsFactory).runSyncUnsafe()) { case Left(error) =>
      assertion(error)
    }
  }
}
