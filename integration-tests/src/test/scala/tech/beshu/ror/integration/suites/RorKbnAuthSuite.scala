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

import io.jsonwebtoken.{JwtBuilder, Jwts, SignatureAlgorithm}
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers.EsContainerCreator
import tech.beshu.ror.utils.elasticsearch.CatManager

import scala.collection.mutable

//TODO change test names. Current names are copies from old java integration tests
trait RorKbnAuthSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest {
  this: EsContainerCreator =>

  private val algo = "HS256"
  private val validKey = "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
  private val validKeyRole = "1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890"
  private val wrongKey = "abcdef"
  private val userClaim = "user"
  private val groupsClaim = "groups"

  override implicit val rorConfigFileName = "/ror_kbn_auth/readonlyrest.yml"

  "rejectRequestWithoutAuthorizationHeader" in {
    val clusterStateManager = new CatManager(noBasicAuthClient, esVersion = targetEs.esVersion)

    val response = clusterStateManager.indices()
    response.responseCode should be(401)
  }

  "rejectTokenWithWrongKey" in {
    val token = makeToken(wrongKey)
    val clusterStateManager = new CatManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer $token"), esVersion = targetEs.esVersion)

    val response = clusterStateManager.indices()
    response.responseCode should be(401)
  }

  "rejectTokenWithoutUserClaim" in {
    val token = makeToken(validKey)
    val clusterStateManager = new CatManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer $token"), esVersion = targetEs.esVersion)

    val response = clusterStateManager.indices()
    response.responseCode should be(401)
  }

  "acceptValidTokenWithUserClaim" in {
    // Groups claim is mandatory, even if empty
    val token = makeTokenWithClaims(validKey, makeClaimMap(userClaim, "user", groupsClaim, ""))
    val clusterStateManager = new CatManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer $token"), esVersion = targetEs.esVersion)

    val response = clusterStateManager.indices()
    response.responseCode should be(200)
  }

  "rejectExpiredToken" in {
    val token = makeTokenWithClaims(validKey, makeClaimMap(userClaim, "user", "exp", 0))
    val clusterStateManager = new CatManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer $token"), esVersion = targetEs.esVersion)

    val response = clusterStateManager.indices()
    response.responseCode should be(401)
  }

  "rejectTokenWithoutRolesClaim" in {
    val token = makeToken(validKeyRole)
    val clusterStateManager = new CatManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer $token"), esVersion = targetEs.esVersion)

    val response = clusterStateManager.indices()
    response.responseCode should be(401)
  }

  "rejectTokenWithWrongRolesClaim" in {
    val token = makeTokenWithClaims(validKeyRole, makeClaimMap(groupsClaim, "wrong_group"))
    val clusterStateManager = new CatManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer $token"), esVersion = targetEs.esVersion)

    val response = clusterStateManager.indices()
    response.responseCode should be(401)
  }

  "acceptValidTokenWithRolesClaim" in {
    val token = makeTokenWithClaims(validKeyRole, makeClaimMap(groupsClaim, "viewer_group"))
    val clusterStateManager = new CatManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer $token"), esVersion = targetEs.esVersion)

    val response = clusterStateManager.indices()
    response.responseCode should be(200)
  }

  private def makeToken(key: String): String = makeTokenWithClaims(key, Map.empty)

  private def makeTokenWithClaims(key: String, claims: scala.collection.Map[String, Any]): String = {
    val builder: JwtBuilder = Jwts.builder.setSubject("test").signWith(SignatureAlgorithm.valueOf(algo), key.getBytes)
    claims.foreach(e => builder.claim(e._1, e._2))
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
    if (!claims.contains(userClaim))
      claims.put(userClaim, "user")

    claims
  }
}
