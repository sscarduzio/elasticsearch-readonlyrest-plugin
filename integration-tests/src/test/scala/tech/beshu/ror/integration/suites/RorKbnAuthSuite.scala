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
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.EsContainerCreator
import tech.beshu.ror.utils.elasticsearch.CatManager
import tech.beshu.ror.utils.misc.JwtUtils._

//TODO change test names. Current names are copies from old java integration tests
trait RorKbnAuthSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupportForAnyWordSpecLike {
  this: EsContainerCreator =>

  private val algo = SignatureAlgorithm.valueOf("HS256")
  private val validKey = "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
  private val validKeyRole = "1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890.1234567890"
  private val wrongKey = "abcdefdsadsadsadsadsadfdsfdsfdsfdsfds"

  override implicit val rorConfigFileName = "/ror_kbn_auth/readonlyrest.yml"

  "rejectRequestWithoutAuthorizationHeader" in {
    val clusterStateManager = new CatManager(noBasicAuthClient, esVersion = esVersionUsed)

    val response = clusterStateManager.indices()
    response.responseCode should be(401)
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
    response.responseCode should be(401)
  }

  "rejectTokenWithoutUserClaim" in {
    val jwt = Jwt(algo, validKey, claims = List.empty)
    val clusterStateManager = new CatManager(
      noBasicAuthClient,
      additionalHeaders = Map("Authorization" -> s"Bearer ${jwt.stringify()}"),
      esVersion = esVersionUsed
    )

    val response = clusterStateManager.indices()
    response.responseCode should be(401)
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
    response.responseCode should be(200)
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
    response.responseCode should be(401)
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
    response.responseCode should be(401)
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
    response.responseCode should be(401)
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
    response.responseCode should be(200)
  }

}
