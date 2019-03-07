package tech.beshu.ror.unit.acl.factory.decoders

import org.scalamock.scalatest.MockFactory
import tech.beshu.ror.acl.blocks.rules.LdapAuthenticationRule

class LdapAuthenticationRuleSettingsTests extends BaseRuleSettingsDecoderTest[LdapAuthenticationRule] with MockFactory {

  "A LdapAuthenticationRule" should {
    "be able to be loaded from config" when {
      "simple version of LDAP authentication rule is used" in {

      }
      "authentication LDAP rule can have caching declared at rule level" in {

      }
    }
    "not be able to be loaded from config" when {
      "no LDAP service with given name is defined" in {

      }
      "extended version of rule definition doesn't declare cache TTL" in {

      }
      "extended version of rule definition cannot be mixes with simple one" in {

      }
    }
  }
}
