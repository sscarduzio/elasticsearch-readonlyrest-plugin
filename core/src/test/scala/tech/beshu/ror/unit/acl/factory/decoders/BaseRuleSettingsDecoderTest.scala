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
import org.scalatest.Matchers.{a, _}
import org.scalatest.{Inside, Suite, WordSpec}
import tech.beshu.ror.acl.SequentialAcl
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.factory.{CoreFactory, CoreSettings, HttpClientsFactory}
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError
import tech.beshu.ror.acl.utils._
import tech.beshu.ror.mocks.MockHttpClientsFactory

import scala.reflect.ClassTag
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.utils.{EnvVarsProvider, OsEnvVarsProvider, JavaUuidProvider, UuidProvider}
import tech.beshu.ror.utils.TestsUtils._

abstract class BaseRuleSettingsDecoderTest[T <: Rule : ClassTag] extends WordSpec with Inside {
  this: Suite =>

  protected def envVarsProvider: EnvVarsProvider = OsEnvVarsProvider

  private val factory = {
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val resolver: StaticVariablesResolver = new StaticVariablesResolver(envVarsProvider)
    new CoreFactory
  }

  def assertDecodingSuccess(yaml: String,
                            assertion: T => Unit,
                            httpClientsFactory: HttpClientsFactory = MockHttpClientsFactory): Unit = {
    inside(factory.createCoreFrom(rorConfigFrom(yaml), httpClientsFactory).runSyncUnsafe()) { case Right(CoreSettings(acl: SequentialAcl, _, _)) =>
      val rule = acl.blocks.head.rules.collect { case r: T => r }.headOption
        .getOrElse(throw new IllegalStateException("There was no expected rule in decoding result"))
      rule shouldBe a[T]
      assertion(rule.asInstanceOf[T])
    }
  }

  def assertDecodingFailure(yaml: String,
                            assertion: NonEmptyList[AclCreationError] => Unit,
                            httpClientsFactory: HttpClientsFactory = MockHttpClientsFactory): Unit = {
    inside(factory.createCoreFrom(rorConfigFrom(yaml), httpClientsFactory).runSyncUnsafe()) { case Left(error) =>
      assertion(error)
    }
  }
}
