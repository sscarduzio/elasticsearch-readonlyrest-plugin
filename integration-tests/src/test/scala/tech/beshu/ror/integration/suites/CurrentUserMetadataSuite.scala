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

import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, SingletonPluginTestSupport}
import tech.beshu.ror.utils.TestUjson.ujson
import tech.beshu.ror.utils.elasticsearch.RorApiManager
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

import java.util.UUID

class CurrentUserMetadataSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/current_user_metadata/readonlyrest.yml"

  "An ACL" when {
    "handling current user metadata kibana plugin request" should {
      "allow to proceed" when {
        "several blocks are matched" in {
          val user1MetadataManager = new RorApiManager(basicAuthClient("user1", "pass"), esVersionUsed)

          val correlationId = UUID.randomUUID().toString
          val result = user1MetadataManager.fetchMetadata(correlationId = Some(correlationId))

          result should have statusCode 200
          result.responseJson should be(ujson.read(
            s"""{
               |  "x-ror-username": "user1",
               |  "x-ror-current-group": {
               |    "id": "group1",
               |    "name": "group1"
               |  },
               |  "x-ror-available-groups": [
               |    {
               |      "id": "group1",
               |      "name": "group1"
               |    }
               |  ],
               |  "x-ror-correlation-id": "$correlationId"
               |}""".stripMargin))
        }
        "several blocks are matched and current group is set" in {
          val user1MetadataManager = new RorApiManager(basicAuthClient("user4", "pass"), esVersionUsed)

          val correlationId = UUID.randomUUID().toString
          val result = user1MetadataManager.fetchMetadata(
            preferredGroupId = Some("group6"),
            correlationId = Some(correlationId)
          )

          result should have statusCode 200
          result.responseJson should be(ujson.read(
            s"""{
               |  "x-ror-username": "user4",
               |  "x-ror-current-group": {
               |    "id": "group6",
               |    "name": "Group 6"
               |  },
               |  "x-ror-available-groups": [
               |    {
               |      "id": "group5",
               |      "name": "Group 5"
               |    },
               |    {
               |      "id": "group6",
               |      "name": "Group 6"
               |    }
               |  ],
               |  "x-ror-correlation-id": "$correlationId",
               |  "x-ror-kibana_index": "user4_group6_kibana_index",
               |  "x-ror-kibana_template_index": "user4_group6_kibana_template_index",
               |  "x-ror-kibana_access":"unrestricted"
               |}""".stripMargin))
        }
        "at least one block is matched" in {
          val user2MetadataManager = new RorApiManager(basicAuthClient("user2", "pass"), esVersionUsed)

          val correlationId = UUID.randomUUID().toString
          val result = user2MetadataManager.fetchMetadata(correlationId = Some(correlationId))

          result should have statusCode 200
          result.responseJson should be(ujson.read(
            s"""{
               |  "x-ror-username": "user2",
               |  "x-ror-current-group": {
               |    "id": "group2",
               |    "name": "group2"
               |  },
               |  "x-ror-available-groups": [
               |    {
               |      "id": "group2",
               |      "name": "group2"
               |    }
               |  ],
               |  "x-ror-correlation-id": "$correlationId",
               |  "x-ror-kibana_index": "user2_kibana_index",
               |  "x-ror-kibana_access": "ro",
               |  "x-ror-kibana-hidden-apps": [ "user2_app1", "user2_app2", "^Analytics\\\\|(?!Maps$$).*" ],
               |  "x-ror-kibana-allowed-api-paths":[
               |    {
               |      "http_method":"ANY",
               |      "path_regex":"^/api/spaces/.*$$"
               |    },
               |    {
               |      "http_method":"GET",
               |      "path_regex":"^/api/spaces\\\\?test\\\\=12\\\\.2$$"
               |    }
               |  ],
               |  "x-ror-kibana-metadata": {
               |    "a": 1,
               |    "b": true,
               |    "c": "text",
               |    "d": [ "a","b" ],
               |    "e": {
               |      "f": 1
               |    },
               |    "g": null
               |  }
               |}""".stripMargin))
        }
        "block with no available groups collected is matched" in {
          val user3MetadataManager = new RorApiManager(basicAuthClient("user3", "pass"), esVersionUsed)

          val correlationId = UUID.randomUUID().toString
          val result = user3MetadataManager.fetchMetadata(correlationId = Some(correlationId))

          result should have statusCode 200
          result.responseJson should be(ujson.read(
            s"""{
               |  "x-ror-username": "user3",
               |  "x-ror-correlation-id": "$correlationId",
               |  "x-ror-kibana_index": "user3_kibana_index",
               |  "x-ror-kibana_access": "unrestricted",
               |  "x-ror-kibana-hidden-apps": [ "user3_app1", "user3_app2" ]
               |}""".stripMargin))
        }
      }
      "return forbidden" when {
        "no block is matched" in {
          val unknownUserMetadataManager = new RorApiManager(basicAuthClient("userXXX", "pass"), esVersionUsed)

          val result = unknownUserMetadataManager.fetchMetadata()

          result should have statusCode 403
        }
        "current group is set but it doesn't exist on available groups list" in {
          val user4MetadataManager = new RorApiManager(basicAuthClient("user4", "pass"), esVersionUsed)

          val result = user4MetadataManager.fetchMetadata(preferredGroupId = Some("group7"))

          result should have statusCode 403
        }
        "block with no available groups collected is matched and current group is set" in {
          val user3MetadataManager = new RorApiManager(basicAuthClient("user3", "pass"), esVersionUsed)

          val result = user3MetadataManager.fetchMetadata(preferredGroupId = Some("group7"))

          result should have statusCode 403
        }
      }
    }
  }
}