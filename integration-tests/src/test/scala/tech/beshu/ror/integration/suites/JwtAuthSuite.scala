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
package tech.beshu.ror.integration.suites

import java.util
import java.util.Date

import com.google.common.collect.Lists
import io.jsonwebtoken.{JwtBuilder, Jwts, SignatureAlgorithm}
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.{EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.ClusterStateManager

import scala.collection.mutable

//TODO change test names. Current names are copies from old java integration tests
trait JwtAuthSuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport {
  this: EsContainerCreator =>

  private val algo = "HS256"
  private val validKey = "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
  private val validKeyRole = "1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890"
  private val wrongKey = "abcdef"
  private val userClaim = "user"
  private val rolesClaim = "roles"

  override implicit val rorConfigFileName = "/jwt_auth/readonlyrest.yml"

  override lazy val targetEs = container.nodesContainers.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1"
    )
  )

  "rejectRequestWithoutAuthorizationHeader" in {
    val clusterStateManager = new ClusterStateManager(noBasicAuthClient)

    val response = clusterStateManager.catIndices()
    response.responseCode should be(401)
  }

  "rejectTokenWithWrongKey" in {
    val token = makeToken(wrongKey)
    val clusterStateManager = new ClusterStateManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer $token"))

    val response = clusterStateManager.catIndices()
    response.responseCode should be(401)
  }

  "rejectTokenWithoutUserClaim" in {
    val token = makeToken(validKey)
    val clusterStateManager = new ClusterStateManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer $token"))

    val response = clusterStateManager.catIndices()
    response.responseCode should be(401)
  }

  "acceptValidTokenWithUserClaim" in {
    val token = makeTokenWithClaims(validKey, makeClaimMap(userClaim, "user"))
    val clusterStateManager = new ClusterStateManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer $token"))

    val response = clusterStateManager.catIndices()
    response.responseCode should be(200)
  }

  "acceptValidTokenWithUserClaimAndCustomHeader" in {
    val token = makeTokenWithClaims(validKey, makeClaimMap(userClaim, "user"))
    val clusterStateManager = new ClusterStateManager(
      noBasicAuthClient,
      additionalHeaders = Map("x-custom-header" -> s"$token"))

    val response = clusterStateManager.catIndices()
    response.responseCode should be(200)
  }

  "acceptValidTokenWithUserClaimAndCustomHeaderAndCustomHeaderPrefix" in {
    val token = makeTokenWithClaims(validKey, makeClaimMap(userClaim, "user"))
    val clusterStateManager = new ClusterStateManager(
      noBasicAuthClient,
      additionalHeaders = Map("x-custom-header2" -> s"x-custom-prefix$token"))

    val response = clusterStateManager.catIndices()
    response.responseCode should be(200)
  }

  "rejectTokenWithUserClaimAndCustomHeader" in {
    val token = makeTokenWithClaims(validKey, makeClaimMap("inexistent_claim", "user"))
    val clusterStateManager = new ClusterStateManager(
      noBasicAuthClient,
      additionalHeaders = Map("x-custom-header" -> s"$token"))

    val response = clusterStateManager.catIndices()
    response.responseCode should be(401)
  }

  "rejectExpiredToken" in {
    val token = makeTokenWithClaims(validKey, makeClaimMap(userClaim, "user", "exp", 0))
    val clusterStateManager = new ClusterStateManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer $token"))

    val response = clusterStateManager.catIndices()
    response.responseCode should be(401)
  }

  "rejectTokenWithoutRolesClaim" in {
    val token = makeToken(validKeyRole)
    val clusterStateManager = new ClusterStateManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer $token"))

    val response = clusterStateManager.catIndices()
    response.responseCode should be(401)
  }

  "rejectTokenWithWrongRolesClaim" in {
    val token = makeTokenWithClaims(validKeyRole, makeClaimMap(rolesClaim, "role_wrong"))
    val clusterStateManager = new ClusterStateManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer $token"))

    val response = clusterStateManager.catIndices()
    response.responseCode should be(401)
  }

  "acceptValidTokenWithRolesClaim" in {
    val roles: util.List[String] = Lists.newArrayList("role_viewer", "something_lol")
    val token = makeTokenWithClaims(validKeyRole, makeClaimMap(userClaim, "user1", rolesClaim, roles))
    val clusterStateManager = new ClusterStateManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer $token"))

    val response = clusterStateManager.catIndices()
    response.responseCode should be(200)
  }

  private def makeToken(key: String): String = makeTokenWithClaims(key, mutable.Map.empty)

  private def makeTokenWithClaims(key: String, claims: mutable.Map[String, Any]): String = {
    val builder: JwtBuilder = Jwts.builder
      .setSubject("test")
      .setExpiration(new Date(System.currentTimeMillis * 2))
      .signWith(SignatureAlgorithm.valueOf(algo), key.getBytes)

    claims.foreach {
      case (key, value) => builder.claim(key, value)
    }
    builder.compact
  }

  private def makeClaimMap(kvs: Any*) = {
    assert(kvs.length % 2 == 0)
    val claims: mutable.Map[String, Any] = mutable.Map.empty
    var i: Int = 0
    while (i < kvs.length) {
      claims.put(kvs(i).asInstanceOf[String], kvs(i + 1))
      i += 2
    }
    claims
  }
}
