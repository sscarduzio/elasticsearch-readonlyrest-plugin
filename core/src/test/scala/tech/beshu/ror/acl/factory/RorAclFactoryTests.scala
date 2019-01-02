package tech.beshu.ror.acl.factory

import org.scalatest.Matchers._
import org.scalatest.WordSpec

class RorAclFactoryTests extends WordSpec {

  "A RorAclFactory" should {
    "one" in {
      val factory = new RorAclFactory()
      val settings =
        """
          |  # Default policy is to forbid everything, so let's define a whitelist
          |  access_control_rules:
          |
          |  # ES container initializer need this rule to configure ES instance after startup
          |  - name: "CONTAINER ADMIN"
          |    type: allow
          |    auth_key: admin:container
          |    ss: 12
          |
          |  - name: 4
          |    type: allow
          |    accept_x-forwarded-for_header: true
          |    hosts: [127.0.0.1, 192.168.1.0/24]
          |    ds: 213
        """.stripMargin
      factory.createAclFrom(settings) should be(Left(RorAclFactory.AclCreationError.UnparsableYamlContent))
    }
    "second" in {

      val factory = new RorAclFactory()
      val settings =
        """
          |  # Default policy is to forbid everything, so let's define a whitelist
          |  access_control_rules:
        """.stripMargin
      factory.createAclFrom(settings) should be(Left(RorAclFactory.AclCreationError.UnparsableYamlContent))
    }
  }
}
