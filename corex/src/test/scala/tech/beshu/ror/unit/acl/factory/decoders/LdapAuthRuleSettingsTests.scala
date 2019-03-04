package tech.beshu.ror.unit.acl.factory.decoders

import org.scalamock.scalatest.MockFactory
import tech.beshu.ror.acl.blocks.rules.LdapAuthRule

class LdapAuthRuleSettingsTests extends BaseRuleSettingsDecoderTest[LdapAuthRule] with MockFactory {

  "A LdapAuthRule" should {
    "be able to be loaded from config" when {
      "there is LDAP service with given name and groups are defined" in {

      }
      "auth LDAP rule can have caching declared at rule level" in {

      }
    }
    "not be able to be loaded from config" when {
      "groups are not defined" in {

      }
      "groups set is empty" in {

      }
      "LDAP service can only handle authentication, not authorization" in {

      }
    }
  }
}
