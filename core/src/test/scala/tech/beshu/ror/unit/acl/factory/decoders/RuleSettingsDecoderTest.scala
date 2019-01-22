package tech.beshu.ror.unit.acl.factory.decoders

import java.time.Clock

import cats.data.NonEmptyList
import org.scalatest.Matchers.{a, _}
import org.scalatest.{Inside, Suite, WordSpec}
import tech.beshu.ror.acl.SequentialAcl
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.factory.{HttpClientsFactory, RorAclFactory}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError
import tech.beshu.ror.acl.utils.{JavaUuidProvider, UuidProvider}
import tech.beshu.ror.mocks.MockHttpClientsFactory

import scala.reflect.ClassTag


abstract class RuleSettingsDecoderTest[T <: Rule : ClassTag] extends WordSpec with Inside {
  this: Suite =>

  protected def httpClientsFactory: HttpClientsFactory = MockHttpClientsFactory

  private val factory = {
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    new RorAclFactory
  }

  def assertDecodingSuccess(yaml: String, assertion: T => Unit): Unit = {
    inside(factory.createAclFrom(yaml, httpClientsFactory)) { case Right(acl: SequentialAcl) =>
      val rule = acl.blocks.head.rules.head
      rule shouldBe a[T]
      assertion(rule.asInstanceOf[T])
    }
  }

  def assertDecodingFailure(yaml: String, assertion: NonEmptyList[AclCreationError] => Unit): Unit = {
    inside(factory.createAclFrom(yaml, httpClientsFactory)) { case Left(error) =>
      assertion(error)
    }
  }
}
