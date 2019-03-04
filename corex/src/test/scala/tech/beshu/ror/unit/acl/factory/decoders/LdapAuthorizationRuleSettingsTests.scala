package tech.beshu.ror.unit.acl.factory.decoders

import org.scalamock.scalatest.MockFactory
import tech.beshu.ror.acl.blocks.rules.LdapAuthorizationRule

class LdapAuthorizationRuleSettingsTests extends BaseRuleSettingsDecoderTest[LdapAuthorizationRule] with MockFactory {

  "A LdapAuthorizationRule" should {
    "be able to be loaded from config" when {
      "there is LDAP service with given name and groups are defined" in {

      }
      "authorization LDAP rule can have caching declared at rule level" in {

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
