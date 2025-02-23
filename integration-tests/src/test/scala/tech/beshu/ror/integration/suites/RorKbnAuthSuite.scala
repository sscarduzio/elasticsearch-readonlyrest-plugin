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

import io.jsonwebtoken.SignatureAlgorithm
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, SingletonPluginTestSupport}
import tech.beshu.ror.utils.elasticsearch.CatManager
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers
import tech.beshu.ror.utils.misc.JwtUtils.*

//TODO change test names. Current names are copies from old java integration tests
class RorKbnAuthSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  private val algo = SignatureAlgorithm.valueOf("HS256")
  private val validKey = "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
  private val validKeyRole = "1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890"
  private val wrongKey = "abcdefdsadsadsadsadsadfdsfdsfdsfdsfds"

  override implicit val rorConfigFileName: String = "/ror_kbn_auth/readonlyrest.yml"

  "rejectRequestWithoutAuthorizationHeader" in {
    val clusterStateManager = new CatManager(noBasicAuthClient, esVersion = esVersionUsed)

    val response = clusterStateManager.indices()
    response should have statusCode 403
  }

  "rejectTokenWithWrongKey" in {
    val jwt = Jwt(algo, wrongKey, claims = List(
      "user" := "user"
    ))
    val clusterStateManager = new CatManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer ${jwt.stringify()}"),
      esVersion = esVersionUsed
    )

    val response = clusterStateManager.indices()
    response should have statusCode 403
  }

  "rejectTokenWithoutUserClaim" in {
    val jwt = Jwt(algo, validKey, claims = List.empty)
    val clusterStateManager = new CatManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer ${jwt.stringify()}"),
      esVersion = esVersionUsed
    )

    val response = clusterStateManager.indices()
    response should have statusCode 403
  }

  "acceptValidTokenWithUserClaim" in {
    // Groups claim is mandatory, even if empty
    val jwt = Jwt(algo, validKey, claims = List(
      "user" := "user",
      "groups" := ""
    ))
    val clusterStateManager = new CatManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer ${jwt.stringify()}"),
      esVersion = esVersionUsed
    )

    val response = clusterStateManager.indices()
    response should have statusCode 200
  }

  "rejectExpiredToken" in {
    val jwt = Jwt(algo, validKey, claims = List(
      "user" := "user",
      "exp" := "0"
    ))
    val clusterStateManager = new CatManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer ${jwt.stringify()}"),
      esVersion = esVersionUsed
    )

    val response = clusterStateManager.indices()
    response should have statusCode 403
  }

  "rejectTokenWithoutRolesClaim" in {
    val jwt = Jwt(algo, validKeyRole, claims = List(
      "user" := "user"
    ))
    val clusterStateManager = new CatManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer ${jwt.stringify()}"),
      esVersion = esVersionUsed
    )

    val response = clusterStateManager.indices()
    response should have statusCode 403
  }

  "rejectTokenWithWrongRolesClaim" in {
    val jwt = Jwt(algo, validKeyRole, claims = List(
      "user" := "user",
      "groups" := "wrong_group"
    ))
    val clusterStateManager = new CatManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer ${jwt.stringify()}"),
      esVersion = esVersionUsed
    )

    val response = clusterStateManager.indices()
    response should have statusCode 403
  }

  "acceptValidTokenWithRolesClaim" in {
    val jwt = Jwt(algo, validKeyRole, claims = List(
      "user" := "user",
      "groups" := "viewer_group"
    ))
    val clusterStateManager = new CatManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer ${jwt.stringify()}"),
      esVersion = esVersionUsed
    )

    val response = clusterStateManager.indices()
    response should have statusCode 200
  }

}
