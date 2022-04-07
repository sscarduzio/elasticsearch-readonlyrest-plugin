/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.unit.acl.factory

import java.time.Clock

import eu.timepit.refined.auto._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorConfigurationIndex, User}
import tech.beshu.ror.accesscontrol.factory.{HttpClientsFactory, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.boot.ReadonlyRest.RorMode
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.configuration.RorConfig.LocalUsers
import tech.beshu.ror.mocks.{MockHttpClientsFactory, MockLdapConnectionPoolProvider}
import tech.beshu.ror.providers._
import tech.beshu.ror.utils.TestsUtils._

import scala.language.implicitConversions

class LocalUsersTest extends AnyWordSpec with Inside {

  "ROR config local users" should {
    "return info that all users are resolved" when {
      "auth key block" in {
        assertLocalUsersFromConfig(
          s"""
             |readonlyrest:
             |  access_control_rules:
             |  - name: test_block1
             |    auth_key: admin:container
             |""".stripMargin,
          expected = allUsersResolved(Set(User.Id("admin")))
        )
      }
      "username used in two rules" in {
        assertLocalUsersFromConfig(
          s"""
             |readonlyrest:
             |  access_control_rules:
             |  - name: test_block1
             |    auth_key: admin:container
             |  - name: test_block2
             |    auth_key: admin:container
             |""".stripMargin,
          expected = allUsersResolved(Set(User.Id("admin")))
        )
      }
      "different users defined in rules" in {
        assertLocalUsersFromConfig(
          s"""
             |readonlyrest:
             |  access_control_rules:
             |  - name: test_block1
             |    auth_key: admin:container
             |  - name: test_block2
             |    auth_key: admin1:container
             |""".stripMargin,
          expected = allUsersResolved(Set(User.Id("admin"), User.Id("admin1")))
        )
      }
      "hashed is only password" in {
        assertLocalUsersFromConfig(
          s"""
             |readonlyrest:
             |  access_control_rules:
             |  - name: test_block1
             |    auth_key_sha1: "user1:d27aaf7fa3c1603948bb29b7339f2559dc02019a"
             |  - name: test_block2
             |    auth_key_sha256: "user2:bdf2f78928097ae90a029c33fe06a83e3a572cb48371fb2de290d1c2ffee010b"
             |""".stripMargin,
          allUsersResolved(Set(User.Id("user1"), User.Id("user2")))
        )
      }
    }
    "return info that unknown users in config" when {
      "hashed username and password" in {
        val config =
          s"""
             |readonlyrest:
             |  access_control_rules:
             |  - name: test_block1
             |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
             |  - name: test_block2
             |    auth_key_sha256: "bdf2f78928097ae90a029c33fe06a83e3a572cb48371fb2de290d1c2ffee010b"
             |  - name: test_block3
             |    auth_key: admin:container
             |""".stripMargin
        assertLocalUsersFromConfig(config, expected = withUnknownUsers(Set(User.Id("admin"))))
      }
      "there is some user with hashed credentials" in {
        val config =
          s"""
             |readonlyrest:
             |  access_control_rules:
             |  - name: test_block1
             |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
             |  - name: test_block2
             |    auth_key: admin:container
             |""".stripMargin
        assertLocalUsersFromConfig(config, withUnknownUsers(Set(User.Id("admin"))))
      }
      "users section defined" in {
        val config =
          s"""
             |readonlyrest:
             |  access_control_rules:
             |  - name: test_block1
             |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
             |
             |  users:
             |  - username: user1
             |    groups: ["group1", "group3"]
             |    auth_key: "user1:pass"
             |
             |  - username: user2
             |    groups: ["group2", "group4"]
             |    auth_key: "user2:pass"
             |
             |  - username: user4
             |    groups: ["group5", "group6"]
             |    auth_key: "user4:pass"
             |""".stripMargin

        assertLocalUsersFromConfig(config, withUnknownUsers(Set.empty))
      }
    }
  }

  private def withUnknownUsers(users: Set[User.Id]) = LocalUsers(users, unknownUsers = true)

  private def allUsersResolved(users: Set[User.Id]) = LocalUsers(users, unknownUsers = false)

  private def assertLocalUsersFromConfig(config: String, expected: LocalUsers) = {
    val rorConfig = rorConfigFromUnsafe(config)
    inside(createCore(rorConfig)) {
      case Right(core) =>
        core.rorConfig.localUsers should be(expected)
    }
  }

  private def createCore(config: RawRorConfig,
                         clientsFactory: HttpClientsFactory = MockHttpClientsFactory) = {
    factory
      .createCoreFrom(
        config,
        RorConfigurationIndex(IndexName.Full(".readonlyrest")),
        clientsFactory,
        MockLdapConnectionPoolProvider,
        NoOpMocksProvider
      )
      .runSyncUnsafe()
  }

  private val factory = {
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val provider: EnvVarsProvider = OsEnvVarsProvider
    implicit val propertiesProvider: PropertiesProvider = JvmPropertiesProvider
    new RawRorConfigBasedCoreFactory(RorMode.Plugin)
  }

}
