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
import tech.beshu.ror.utils.containers.EsContainerCreator
import tech.beshu.ror.utils.elasticsearch.{CatManager, ClusterManager, RorApiManager}
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers
import ujson.Str

//TODO change test names. Current names are copies from old java integration tests
trait LocalGroupsSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with Matchers
    with CustomScalaTestMatchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/local_groups/readonlyrest.yml"

  "good credentials but with non matching preferred group are sent" in {
    val clusterManager = new ClusterManager(
      basicAuthClient("user", "passwd"),
      esVersion = esVersionUsed,
      additionalHeaders =  Map("x-ror-current-group" -> "group_extra")
    )

    val response = clusterManager.state()

    response.responseCode should be(401)
  }

  "bad credentials, good rule" in {
    val clusterManager = new ClusterManager(
      basicAuthClient("user", "wrong"),
      esVersion = esVersionUsed,
      additionalHeaders =  Map("x-ror-current-group" -> "group_extra")
    )

    val response = clusterManager.state()

    response.responseCode should be(401)
  }

  "bad credentials, bad rule" in {
    val catManager = new CatManager(
      basicAuthClient("user", "wrong"),
      esVersion = esVersionUsed
    )

    val response = catManager.indices()

    response.responseCode should be(401)
  }

  "identify retrieval" in {
    val userMetadataManager = new RorApiManager(basicAuthClient("user", "passwd"))

    val response = userMetadataManager.fetchMetadata()

    response.responseCode should be(200)
    response.responseJson.obj.size should be(7)
    response.responseJson("x-ror-username").str should be("user")
    response.responseJson("x-ror-current-group").str should be("a_testgroup")
    response.responseJson("x-ror-available-groups").arr.toList should be(List(Str("a_testgroup"), Str("foogroup")))
    response.responseJson("x-ror-kibana_index").str should be(".kibana_user")
    response.responseJson("x-ror-kibana-hidden-apps").arr.toList should be(List(Str("timelion")))
    response.responseJson("x-ror-kibana_access").str should be("admin")
    response.responseJson("x-ror-logging-id").str should fullyMatch uuidRegex()
  }

  "identify retrieval with preferred group" in {
    val userMetadataManager = new RorApiManager(basicAuthClient("user", "passwd"))

    val response = userMetadataManager.fetchMetadata(preferredGroup = "foogroup")

    response.responseCode should be(200)
    response.responseJson.obj.size should be(7)
    response.responseJson("x-ror-username").str should be("user")
    response.responseJson("x-ror-current-group").str should be("foogroup")
    response.responseJson("x-ror-available-groups").arr.toList should be(List(Str("a_testgroup"), Str("foogroup")))
    response.responseJson("x-ror-kibana_index").str should be(".kibana_foogroup")
    response.responseJson("x-ror-kibana-hidden-apps").arr.toList should be(List(Str("foo:app")))
    response.responseJson("x-ror-kibana_access").str should be("admin")
    response.responseJson("x-ror-logging-id").str should fullyMatch uuidRegex()
  }
}
