package tech.beshu.ror.acl.factory

import cats.data.NonEmptyList
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.{ProxyAuthConfigsCreationError, UnparsableYamlContent}

class RorAclFactoryTests extends WordSpec {

  private val factory = new RorAclFactory()

  "A RorAclFactory" should {
    "return unparsable content error" when {
      "config is not valid yaml" in {
        val yaml =
          """
            |  access_control_rules:
            |
            |  - name: "CONTAINER ADMIN"
            |     - name1: "CONTAINER ADMIN"
            |    type: allow
          """.stripMargin
        val acl = factory.createAclFrom(yaml)
        acl should be(Left(NonEmptyList.one(UnparsableYamlContent(yaml))))
      }
    }
    "return proxy auth configs error" when {
      "the section exists, but not contain any element" in {
        val yaml =
          """
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: allow
            |    auth_key: admin:container
            |
            |  proxy_auth_configss:
            |
            |""".stripMargin
        val acl = factory.createAclFrom(yaml)
        acl should be(Left(NonEmptyList.one(ProxyAuthConfigsCreationError("test"))))
      }
    }
  }
}
