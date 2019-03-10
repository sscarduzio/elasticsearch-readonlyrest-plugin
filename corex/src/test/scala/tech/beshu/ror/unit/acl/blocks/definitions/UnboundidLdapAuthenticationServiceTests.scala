package tech.beshu.ror.unit.acl.blocks.definitions

import cats.data._
import com.dimafeng.testcontainers.{Container, ForAllTestContainer, MultipleContainers}
import eu.timepit.refined.api.Refined
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.blocks.definitions.ldap.Dn
import tech.beshu.ror.acl.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations.LdapConnectionConfig.{BindRequestUser, ConnectionMethod, HaMethod, LdapHost}
import tech.beshu.ror.acl.blocks.definitions.ldap.implementations._
import tech.beshu.ror.acl.domain.{Secret, User}
import tech.beshu.ror.acl.utils.ScalaOps
import tech.beshu.ror.acl.utils.ScalaOps.repeat
import tech.beshu.ror.utils.LdapContainer
import tech.beshu.ror.utils.TestsUtils._

import scala.concurrent.duration._
import scala.language.postfixOps

class UnboundidLdapAuthenticationServiceTests extends WordSpec with ForAllTestContainer with Inside {

  private val ldap1Container = new LdapContainer("LDAP1", "/test_example.ldif")
  private val ldap2Container = new LdapContainer("LDAP2", "/test_example.ldif")
  override val container: Container = MultipleContainers(ldap1Container, ldap2Container)

  "An LdapAuthenticationService" should {
    "has method to authenticate" which {
      "returns true" when {
        "user exists in LDAP and its credentials are correct" in {
          simpleAuthenticationService.authenticate(User.Id("morgan"), Secret("user1")).runSyncUnsafe() should be(true)
        }
      }
      "returns false" when {
        "user doesn't exist in LDAP" in {
          simpleAuthenticationService.authenticate(User.Id("unknown"), Secret("user1")).runSyncUnsafe() should be(false)
        }
        "user has invalid credentials" in {
          simpleAuthenticationService.authenticate(User.Id("morgan"), Secret("invalid_secret")).runSyncUnsafe() should be(false)
        }
      }
    }
    "be able to work" when {
      "Round robin HA method is configured" when {
        "one of servers goes down" in {
          def assertMorganCanAuthenticate() = {
            simpleAuthenticationService.authenticate(User.Id("morgan"), Secret("user1")).runSyncUnsafe() should be(true)
          }
          (for {
            _ <- repeat(maxRetries = 5, delay = 500 millis) {
                Task(assertMorganCanAuthenticate())
              }
            _ <- Task(ldap2Container.stop())
            _ <- repeat(10, 500 millis) {
              Task(assertMorganCanAuthenticate())
            }
          } yield ()) runSyncUnsafe()
        }
      }
    }
  }

  private def simpleAuthenticationService = {
    UnboundidLdapAuthenticationService
      .create(
        Name("my_ldap".nonempty),
        LdapConnectionConfig(
          ConnectionMethod.SingleServer(LdapHost.from(s"ldap://${ldap1Container.ldapHost}:${ldap1Container.ldapPort}").get),
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

  private def haAuthenticationService = {
    UnboundidLdapAuthenticationService
      .create(
        Name("my_ldap".nonempty),
        LdapConnectionConfig(
          ConnectionMethod.SeveralServers(
            NonEmptySet.of(
              LdapHost.from(s"ldap://${ldap1Container.ldapHost}:${ldap1Container.ldapPort}").get,
              LdapHost.from(s"ldap://${ldap2Container.ldapHost}:${ldap2Container.ldapPort}").get,
            ),
            HaMethod.RoundRobin
          ),
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
