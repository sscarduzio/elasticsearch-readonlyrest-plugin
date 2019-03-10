package tech.beshu.ror.unit.acl.blocks.definitions

import com.dimafeng.testcontainers.ForAllTestContainer
import eu.timepit.refined.api.Refined
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.blocks.definitions.ldap.Dn
import tech.beshu.ror.acl.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations.LdapConnectionConfig.{BindRequestUser, ConnectionMethod, LdapHost}
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations._
import tech.beshu.ror.acl.domain.{Secret, User}
import tech.beshu.ror.utils.LdapContainer
import tech.beshu.ror.utils.TestsUtils._

import scala.concurrent.duration._
import scala.language.postfixOps

class UnboundidLdapAuthenticationServiceTests extends WordSpec with ForAllTestContainer with Inside {

  override val container: LdapContainer = new LdapContainer("LDAP1", "/test_example.ldif")

  "An LdapAuthenticationService" should {
    "has method to authenticate" which {
      "returns true" when {
        "user exists in LDAP and its credentials are correct" in {
          authenticationService.authenticate(User.Id("morgan"), Secret("user1")).runSyncUnsafe() should be(true)
        }
      }
      "returns false" when {
        "user doesn't exist in LDAP" in {
          authenticationService.authenticate(User.Id("unknown"), Secret("user1")).runSyncUnsafe() should be(false)
        }
        "user has invalid credentials" in {
          authenticationService.authenticate(User.Id("morgan"), Secret("invalid_secret")).runSyncUnsafe() should be(false)
        }
      }
    }
  }

  private def authenticationService = {
    UnboundidLdapAuthenticationService
      .create(
        Name("ldap1".nonempty),
        LdapConnectionConfig(
          ConnectionMethod.SingleServer(LdapHost.from(s"ldap://${container.ldapHost}:${container.ldapPort}").get),
          Refined.unsafeApply(10),
          Refined.unsafeApply(5 seconds),
          Refined.unsafeApply(5 seconds),
          trustAllCerts = false,
          BindRequestUser.CustomUser(
            Dn("cn=admin,dc=example,dc=com".nonempty),
            Secret("password")
          )
        ),
        UserSearchFilterConfig(Dn("ou=People,dc=example,dc=com".nonempty), "uid".nonempty)
      )
      .runSyncUnsafe()
      .right.getOrElse(throw new IllegalStateException("LDAP connection problem"))
  }

}
