package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptyList
import org.scalatest.Matchers.{a, _}
import org.scalatest.{Inside, Suite, WordSpec}
import tech.beshu.ror.unit.acl.SequentialAcl
import tech.beshu.ror.unit.acl.blocks.rules.Rule
import tech.beshu.ror.unit.acl.factory.RorAclFactory
import tech.beshu.ror.unit.acl.factory.RorAclFactory.AclCreationError

import scala.reflect.ClassTag


abstract class RuleSettingsDecoderTest[T <: Rule : ClassTag] extends WordSpec with Inside {
  this: Suite =>

  private val factory = new RorAclFactory()

  def assertDecodingSuccess(yaml: String, assertion: T => Unit): Unit = {
    inside(factory.createAclFrom(yaml)) { case Right(acl: SequentialAcl) =>
      val rule = acl.blocks.head.rules.head
      rule shouldBe a[T]
      assertion(rule.asInstanceOf[T])
    }
  }

  def assertDecodingFailure(yaml: String, assertion: NonEmptyList[AclCreationError] => Unit): Unit = {
    inside(factory.createAclFrom(yaml)) { case Left(error) =>
      assertion(error)
    }
  }
}
