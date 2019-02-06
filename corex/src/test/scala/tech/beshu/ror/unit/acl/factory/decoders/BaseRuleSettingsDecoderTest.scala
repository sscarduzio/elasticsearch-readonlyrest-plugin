package tech.beshu.ror.unit.acl.factory.decoders

import java.time.Clock

import cats.data.NonEmptyList
import org.scalatest.Matchers.{a, _}
import org.scalatest.{Inside, Suite, WordSpec}
import tech.beshu.ror.acl.SequentialAcl
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.factory.{HttpClientsFactory, RorAclFactory}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError
import tech.beshu.ror.acl.utils._
import tech.beshu.ror.mocks.MockHttpClientsFactory

import scala.reflect.ClassTag


abstract class BaseRuleSettingsDecoderTest[T <: Rule : ClassTag] extends WordSpec with Inside {
  this: Suite =>

  protected def envVarsProvider: EnvVarsProvider = JavaEnvVarsProvider

  private val factory = {
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val resolver: StaticVariablesResolver = new StaticVariablesResolver(envVarsProvider)
    new RorAclFactory
  }

  def assertDecodingSuccess(yaml: String,
                            assertion: T => Unit,
                            httpClientsFactory: HttpClientsFactory = MockHttpClientsFactory): Unit = {
    inside(factory.createAclFrom(yaml, httpClientsFactory)) { case Right((acl: SequentialAcl, _)) =>
      val rule = acl.blocks.head.rules.head
      rule shouldBe a[T]
      assertion(rule.asInstanceOf[T])
    }
  }

  def assertDecodingFailure(yaml: String,
                            assertion: NonEmptyList[AclCreationError] => Unit,
                            httpClientsFactory: HttpClientsFactory = MockHttpClientsFactory): Unit = {
    inside(factory.createAclFrom(yaml, httpClientsFactory)) { case Left(error) =>
      assertion(error)
    }
  }
}
