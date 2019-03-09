package tech.beshu.ror.unit.acl.blocks.definitions

import com.dimafeng.testcontainers.ForAllTestContainer
import eu.timepit.refined.api.Refined
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{Inside, WordSpec}
import org.scalatest.Matchers._
import tech.beshu.ror.acl.blocks.definitions.ldap.Dn
import tech.beshu.ror.acl.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations.LdapConnectionConfig.{BindRequestUser, ConnectionMethod, LdapHost}
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations.{LdapConnectionConfig, UnboundidLdapAuthenticationService, UserSearchFilterConfig}
import tech.beshu.ror.acl.domain.{Secret, User}
import tech.beshu.ror.utils.LdapContainer
import tech.beshu.ror.utils.TestsUtils._

import scala.concurrent.duration._
import scala.language.postfixOps

class UnboundidLdapAuthenticationServiceTests extends WordSpec with ForAllTestContainer with Inside {

  override val container: LdapContainer = new LdapContainer("LDAP1", "/test_example.ldif")

  "A LdapAuthService" should {
    "allow to find user by id" in {
      val ldapServiceTask = UnboundidLdapAuthenticationService.create(
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
      inside(ldapServiceTask.runSyncUnsafe()) { case Right(service) =>
        service.authenticate(User.Id("morgan"), Secret("user1")).runSyncUnsafe() should be (true)
      }
    }
  }
}
