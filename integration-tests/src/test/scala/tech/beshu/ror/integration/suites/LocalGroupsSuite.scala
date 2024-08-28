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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, SingletonPluginTestSupport}
import tech.beshu.ror.utils.elasticsearch.{CatManager, ClusterManager, RorApiManager}
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

import java.util.UUID

//TODO change test names. Current names are copies from old java integration tests
class LocalGroupsSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with Matchers
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/local_groups/readonlyrest.yml"

  "good credentials but with non matching preferred group are sent" in {
    val clusterManager = new ClusterManager(
      basicAuthClient("user", "passwd"),
      esVersion = esVersionUsed,
      additionalHeaders = Map("x-ror-current-group" -> "group_extra")
    )

    val response = clusterManager.state()

    response should have statusCode 403
  }

  "bad credentials, good rule" in {
    val clusterManager = new ClusterManager(
      basicAuthClient("user", "wrong"),
      esVersion = esVersionUsed,
      additionalHeaders = Map("x-ror-current-group" -> "group_extra")
    )

    val response = clusterManager.state()

    response should have statusCode 403
  }

  "bad credentials, bad rule" in {
    val catManager = new CatManager(
      basicAuthClient("user", "wrong"),
      esVersion = esVersionUsed
    )

    val response = catManager.indices()

    response should have statusCode 403
  }

  "identify retrieval" in {
    val userMetadataManager = new RorApiManager(basicAuthClient("user", "passwd"), esVersionUsed)

    val correlationId = UUID.randomUUID().toString
    val response = userMetadataManager.fetchMetadata(correlationId = Some(correlationId))

    response should have statusCode 200
    response.responseJson should be(ujson.read(
      s"""{
         |  "x-ror-username": "user",
         |  "x-ror-current-group": {
         |    "id": "a_testgroup",
         |    "name": "Test group"
         |  },
         |  "x-ror-available-groups": [
         |    {
         |      "id": "a_testgroup",
         |      "name": "Test group"
         |    },
         |    {
         |      "id": "foogroup",
         |      "name": "Foo group"
         |    }
         |  ],
         |  "x-ror-correlation-id": "$correlationId",
         |  "x-ror-kibana_index": ".kibana_user",
         |  "x-ror-kibana-hidden-apps": [ "timelion" ],
         |  "x-ror-kibana_access": "admin"
         |}""".stripMargin))
  }

  "identify retrieval with preferred group" in {
    val userMetadataManager = new RorApiManager(basicAuthClient("user", "passwd"), esVersionUsed)

    val correlationId = UUID.randomUUID().toString
    val response = userMetadataManager.fetchMetadata(preferredGroupId = Some("foogroup"), correlationId = Some(correlationId))

    response should have statusCode 200
    response.responseJson should be(ujson.read(
      s"""{
         |  "x-ror-username": "user",
         |  "x-ror-current-group": {
         |    "id": "foogroup",
         |    "name": "Foo group"
         |  },
         |  "x-ror-available-groups": [
         |    {
         |      "id": "a_testgroup",
         |      "name": "Test group"
         |    },
         |    {
         |      "id": "foogroup",
         |      "name": "Foo group"
         |    }
         |  ],
         |  "x-ror-correlation-id": "$correlationId",
         |  "x-ror-kibana_index": ".kibana_foogroup",
         |  "x-ror-kibana-hidden-apps": [ "foo:app" ],
         |  "x-ror-kibana_access": "admin"
         |}""".stripMargin))
  }
}
