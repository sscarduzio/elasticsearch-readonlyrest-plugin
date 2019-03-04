package tech.beshu.ror.unit.acl.factory.decoders.definitions

import org.scalatest.WordSpecLike
import tech.beshu.ror.acl.factory.decoders.definitions.LdapServicesDecoder

class LdapServicesSettingsTests
  extends BaseDecoderTest(LdapServicesDecoder.ldapServicesDefinitionsDecoder)
    with WordSpecLike {

  "A LdapService" should {
    "be able to be loaded from config" when {
      "one LDAP service is declared" in {

      }
      "two LDAP services are declared" in {

      }
      "LDAP service definition is declared only using required fields" in {

      }
      "only LDAP authentication service fields are defined" in {

      }
      "custom bind request user is defined" in {

      }
      "only one LDAP host is defined" in {

      }
      "custom port of single LDAP host is defined" in {

      }
      "two LDAP hosts are defined" in {

      }
      "ROUND_ROBIN HA method is defined" in {

      }
      "custom pool size is configured" in {

      }
      "custom connection timeout is configured" in {

      }
      "custom request timeout is configured" in {

      }
      "default group search mode is used" in {

      }
      "groups from user attribute mode is used" in {

      }
    }
    "not be able to be loaded from config" when {
      "no LDAP service is defined" in {

      }
      "no LDAP service with given name is defined" in {

      }
      "LDAP service doesn't have a name" in {

      }
      "names of LDAP services are not unique" in {

      }
      "LDAP service TTL value is malformed" in {

      }
      "LDAP service TTL value is negative" in {

      }
      "only DN field of custom bind request user is defined" in {

      }
      "only password field of custom bind request user is defined" in {

      }
      "no LDAP host is defined" in {

      }
      "single host settings and multi hosts settings are used in the same time" in {

      }
      "HA method is used for single LDAP host settings" in {

      }
      "unknown HA method is defined" in {

      }
      "host is malformed" in {

      }
      "one of host is malformed" in {

      }
      "connection pool size is malformed" in {

      }
      "one of LDAP services are unavailable" in {

      }
      "connection timeout is malformed" in {

      }
      "request timeout is malformed" in {

      }
      "default group search mode is malformed" in {

      }
      "groups from user attribute mode is used" in {

      }
      "both modes are configured" in {

      }
    }
  }

}
