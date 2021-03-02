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
package tech.beshu.ror.integration

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.impl.DefaultClaims
import io.jsonwebtoken.security.Keys
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.Inside
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{JwtTokenPayload, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._

import scala.collection.JavaConverters._

class RorKbnAuthYamlLoadedAccessControlTests extends AnyWordSpec with BaseYamlLoadedAccessControlTest with MockFactory with Inside {

  override protected def configYaml: String =
    """http.bind_host: _eth0:ipv4_
      |network.host: _eth0:ipv4_
      |
      |http.type: ssl_netty4
      |#transport.type: local
      |
      |readonlyrest:
      |  ssl:
      |    enable: true
      |    keystore_file: "keystore.jks"
      |    keystore_pass: readonlyrest
      |    key_pass: readonlyrest
      |
      |  access_control_rules:
      |    - name: Container housekeeping is allowed
      |      type: allow
      |      auth_key: admin:container
      |
      |    - name: Valid JWT token is present
      |      type: allow
      |      ror_kbn_auth:
      |        name: "kbn1"
      |
      |    - name: Valid JWT token is present with another key
      |      type: allow
      |      ror_kbn_auth:
      |        name: "kbn2"
      |
      |    - name: Valid JWT token is present with a third key + role
      |      type: allow
      |      ror_kbn_auth:
      |        name: "kbn3"
      |        roles: ["viewer_group"]
      |
      |  ror_kbn:
      |
      |    - name: kbn1
      |      signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
      |
      |    - name: kbn2
      |      signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
      |
      |    - name: kbn3
      |      signature_key: "1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890"
      |
    """.stripMargin

  "A ACL" when {
    "is configured using config above" should {
      "allow to proceed" when {
        "JWT token with empty list of groups is defined" in {
          val claims = new DefaultClaims(Map[String, AnyRef]("sub" -> "test", "user" -> "user", "groups" -> "").asJava)
          val jwtBuilder = Jwts.builder
            .signWith(Keys.hmacShaKeyFor("123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456".getBytes))
            .setSubject("test")
            .setClaims(claims)
          val request = MockRequestContext.indices.copy(headers = Set(header("Authorization", s"Bearer ${jwtBuilder.compact}")))

          val result = acl.handleRegularRequest(request).runSyncUnsafe()

          result.history should have size 2
          inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
            block.name should be(Block.Name("Valid JWT token is present"))
            assertBlockContext(
              loggedUser = Some(DirectlyLoggedUser(User.Id("user".nonempty))),
              jwt = Some(JwtTokenPayload(claims))
            ) {
              blockContext
            }
          }
        }
      }
    }
  }
}
