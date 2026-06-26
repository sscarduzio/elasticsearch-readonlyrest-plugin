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

class UserMetadataSuite
    extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  override implicit val rorSettingsFileName: String = "/user_metadata/readonlyrest.yml"

  "An ACL" when {
    "handling user metadata kibana plugin request (without ROR metadata)" should {
      "allow to proceed" when {
        "several blocks are matched (case 1)" in {
          val userMetadataManager = new RorApiManager(basicAuthClient("user1", "pass"), esVersionUsed)

          val correlationId = UUID.randomUUID().toString
          val result = userMetadataManager.fetchUserMetadata("ent", correlationId = Some(correlationId))

          result should have statusCode 200
          result.responseJson should be(ujson.read(s"""
                                                      |{
                                                      |  "type":"USER_WITH_GROUPS",
                                                      |  "correlation_id":"$correlationId",
                                                      |  "groups":[
                                                      |    {
                                                      |      "group":{
                                                      |        "id":"group1",
                                                      |        "name":"group1"
                                                      |        },
                                                      |      "username":"user1",
                                                      |      "kibana":{"access":"unrestricted","index":".kibana"}
                                                      |    }
                                                      |  ]
                                                      |}
                                                      |""".stripMargin))
        }
        "several blocks are matched (case 2)" in {
          val userMetadataManager = new RorApiManager(basicAuthClient("user4", "pass"), esVersionUsed)

          val correlationId = UUID.randomUUID().toString
          val result = userMetadataManager.fetchUserMetadata("ent", correlationId = Some(correlationId))

          result should have statusCode 200
          result.responseJson should be(ujson.read(s"""
                                                      |{
                                                      |  "type":"USER_WITH_GROUPS",
                                                      |  "correlation_id":"$correlationId",
                                                      |  "groups":[
                                                      |    {
                                                      |      "group":{"id":"group5","name":"Group 5"},
                                                      |      "username":"user4",
                                                      |      "kibana":{
                                                      |        "access":"unrestricted",
                                                      |        "index":"user4_group5_kibana_index"
                                                      |      }
                                                      |    },
                                                      |    {
                                                      |      "group":{"id":"group6","name":"Group 6"},
                                                      |      "username":"user4",
                                                      |      "kibana":{
                                                      |        "access":"unrestricted",
                                                      |        "index":"user4_group6_kibana_index",
                                                      |        "template_index":"user4_group6_kibana_template_index"
                                                      |      }
                                                      |    }
                                                      |  ]
                                                      |}
                                                      |""".stripMargin))
        }
        "at least one block is matched" in {
          val userMetadataManager = new RorApiManager(basicAuthClient("user2", "pass"), esVersionUsed)

          val correlationId = UUID.randomUUID().toString
          val result = userMetadataManager.fetchUserMetadata("ent", correlationId = Some(correlationId))

          result should have statusCode 200
          result.responseJson should be(
            ujson.read(s"""
                          |{
                          |  "type":"USER_WITH_GROUPS",
                          |  "correlation_id":"$correlationId",
                          |  "groups":[
                          |    {
                          |      "group":{"id":"group2","name":"group2"},
                          |      "username":"user2",
                          |      "kibana":{
                          |        "access":"api_only",
                          |        "index":"user2_kibana_index",
                          |        "hidden_apps":["user2_app1","user2_app2","/^Analytics\\\\|(?!(Maps)$$).*$$/"],
                          |        "allowed_api_paths":[
                          |          {"http_method":"ANY","path_regex":"^/api/spaces/.*$$"},
                          |          {"http_method":"GET","path_regex":"^/api/spaces\\\\?test\\\\=12\\\\.2$$"}
                          |        ],
                          |        "metadata":{"e":{"f":1},"a":1,"b":true,"c":"text","d":["a","b"]}
                          |      }
                          |    }
                          |  ]
                          |}
                          |""".stripMargin)
          )
        }
        "single block with multiple groups using @{acl:current_group} variable" in {
          val userMetadataManager = new RorApiManager(basicAuthClient("user6", "pass"), esVersionUsed)

          val correlationId = UUID.randomUUID().toString
          val result = userMetadataManager.fetchUserMetadata("ent", correlationId = Some(correlationId))

          result should have statusCode 200
          result.responseJson should be(ujson.read(s"""
                                                      |{
                                                      |  "type":"USER_WITH_GROUPS",
                                                      |  "correlation_id":"$correlationId",
                                                      |  "groups":[
                                                      |    {
                                                      |      "group":{"id":"group8","name":"group8"},
                                                      |      "username":"user6",
                                                      |      "kibana":{
                                                      |        "access":"unrestricted",
                                                      |        "index":"user6_group8_kibana_index",
                                                      |        "template_index":"user6_group8_kibana_template_index"
                                                      |      }
                                                      |    },
                                                      |    {
                                                      |      "group":{"id":"group9","name":"group9"},
                                                      |      "username":"user6",
                                                      |      "kibana":{
                                                      |        "access":"unrestricted",
                                                      |        "index":"user6_group9_kibana_index",
                                                      |        "template_index":"user6_group9_kibana_template_index"
                                                      |      }
                                                      |    }
                                                      |  ]
                                                      |}
                                                      |""".stripMargin))
        }
        "broad multi-group block with no explicit index defers to specific per-group blocks" in {
          val userMetadataManager = new RorApiManager(basicAuthClient("user7", "pass"), esVersionUsed)

          val correlationId = UUID.randomUUID().toString
          val result = userMetadataManager.fetchUserMetadata("ent", correlationId = Some(correlationId))

          result should have statusCode 200
          result.responseJson should be(ujson.read(s"""
                                                      |{
                                                      |  "type":"USER_WITH_GROUPS",
                                                      |  "correlation_id":"$correlationId",
                                                      |  "groups":[
                                                      |    {
                                                      |      "group":{"id":"group10","name":"group10"},
                                                      |      "username":"user7",
                                                      |      "kibana":{
                                                      |        "access":"unrestricted",
                                                      |        "index":".kibana"
                                                      |      }
                                                      |    },
                                                      |    {
                                                      |      "group":{"id":"group11","name":"group11"},
                                                      |      "username":"user7",
                                                      |      "kibana":{
                                                      |        "access":"rw",
                                                      |        "index":"user7_group11_kibana_index"
                                                      |      }
                                                      |    },
                                                      |    {
                                                      |      "group":{"id":"group12","name":"group12"},
                                                      |      "username":"user7",
                                                      |      "kibana":{
                                                      |        "access":"rw",
                                                      |        "index":"user7_group12_kibana_index"
                                                      |      }
                                                      |    }
                                                      |  ]
                                                      |}
                                                      |""".stripMargin))
        }
        "wildcard groups block resolves @{acl:current_group} per group" in {
          val userMetadataManager = new RorApiManager(basicAuthClient("user8", "pass"), esVersionUsed)

          val correlationId = UUID.randomUUID().toString
          val result = userMetadataManager.fetchUserMetadata("ent", correlationId = Some(correlationId))

          result should have statusCode 200
          result.responseJson should be(ujson.read(s"""
                                                      |{
                                                      |  "type":"USER_WITH_GROUPS",
                                                      |  "correlation_id":"$correlationId",
                                                      |  "groups":[
                                                      |    {
                                                      |      "group":{"id":"group13","name":"group13"},
                                                      |      "username":"user8",
                                                      |      "kibana":{
                                                      |        "access":"unrestricted",
                                                      |        "index":"user8_group13_kibana_index",
                                                      |        "hidden_apps":["user8_app1"]
                                                      |      }
                                                      |    },
                                                      |    {
                                                      |      "group":{"id":"group14","name":"group14"},
                                                      |      "username":"user8",
                                                      |      "kibana":{
                                                      |        "access":"unrestricted",
                                                      |        "index":"user8_group14_kibana_index",
                                                      |        "hidden_apps":["user8_app1"]
                                                      |      }
                                                      |    },
                                                      |    {
                                                      |      "group":{"id":"group15","name":"group15"},
                                                      |      "username":"user8",
                                                      |      "kibana":{
                                                      |        "access":"unrestricted",
                                                      |        "index":"user8_group15_kibana_index",
                                                      |        "hidden_apps":["user8_app1"]
                                                      |      }
                                                      |    }
                                                      |  ]
                                                      |}
                                                      |""".stripMargin))
        }
        "block with no available groups collected is matched" in {
          val userMetadataManager = new RorApiManager(basicAuthClient("user3", "pass"), esVersionUsed)

          val correlationId = UUID.randomUUID().toString
          val result = userMetadataManager.fetchUserMetadata("ent", correlationId = Some(correlationId))

          result should have statusCode 200
          result.responseJson should be(ujson.read(s"""
                                                      |{
                                                      |  "type":"USER_WITHOUT_GROUPS",
                                                      |  "correlation_id":"$correlationId",
                                                      |  "username":"user3",
                                                      |  "kibana":{
                                                      |    "access":"unrestricted",
                                                      |    "index":"user3_kibana_index",
                                                      |    "hidden_apps":["user3_app1","user3_app2"]
                                                      |  }
                                                      |}
                                                      |""".stripMargin))
        }
      }
      "return forbidden" when {
        "no block is matched" in {
          val unknownUserMetadataManager = new RorApiManager(basicAuthClient("userXXX", "pass"), esVersionUsed)

          val result = unknownUserMetadataManager.fetchUserMetadata("ent")

          result should have statusCode 403
          result.responseJson should be(
            ujson.read(
              s"""
                 |{
                 |  "error":{
                 |    "root_cause":[
                 |      {
                 |        "type":"forbidden_response",
                 |        "reason":"Forbidden by ReadonlyREST",
                 |        "due_to":"OPERATION_NOT_ALLOWED"
                 |      }
                 |    ],
                 |    "type":"forbidden_response",
                 |    "reason":"Forbidden by ReadonlyREST",
                 |    "due_to":"OPERATION_NOT_ALLOWED"
                 |  },
                 |  "status":403
                 |}
                 |""".stripMargin
            )
          )
        }
        "forbid block is matched" in {
          val userMetadataManager = new RorApiManager(basicAuthClient("user5", "pass"), esVersionUsed)

          val result = userMetadataManager.fetchUserMetadata("ent")

          result should have statusCode 403
          result.responseJson should be(
            ujson.read(
              s"""
                 |{
                 |  "error":{
                 |    "root_cause":[
                 |      {
                 |        "type":"forbidden_response",
                 |        "reason":"you are unauthorized to access this resource",
                 |        "due_to":"FORBIDDEN_BY_BLOCK"
                 |      }
                 |    ],
                 |    "type":"forbidden_response",
                 |    "reason":"you are unauthorized to access this resource",
                 |    "due_to":"FORBIDDEN_BY_BLOCK"
                 |  },
                 |  "status":403
                 |}
                 |""".stripMargin
            )
          )
        }
      }
    }
    "handling user metadata kibana plugin request (with ROR metadata)" should {
      "allow to proceed" when {
        "several blocks are matched (case 1)" in {
          val correlationId = UUID.randomUUID().toString

          val userMetadataManager = new RorApiManager(
            basicAuthClientWithRorMetadataAttached(
              "user1",
              "pass",
              ("x-ror-correlation-id", correlationId),
              ("x-ror-kbn-license-type", "ent")
            ),
            esVersionUsed
          )

          val result = userMetadataManager.fetchUserMetadata()

          result should have statusCode 200
          result.responseJson should be(ujson.read(s"""
                                                      |{
                                                      |  "type":"USER_WITH_GROUPS",
                                                      |  "correlation_id":"$correlationId",
                                                      |  "groups":[
                                                      |    {
                                                      |      "group":{
                                                      |        "id":"group1",
                                                      |        "name":"group1"
                                                      |        },
                                                      |      "username":"user1",
                                                      |      "kibana":{"access":"unrestricted","index":".kibana"}
                                                      |    }
                                                      |  ]
                                                      |}
                                                      |""".stripMargin))
        }
        "several blocks are matched (case 2)" in {
          val correlationId = UUID.randomUUID().toString
          val userMetadataManager = new RorApiManager(
            basicAuthClientWithRorMetadataAttached(
              "user4",
              "pass",
              ("x-ror-correlation-id", correlationId),
              ("x-ror-kbn-license-type", "ent")
            ),
            esVersionUsed
          )

          val result = userMetadataManager.fetchUserMetadata()

          result should have statusCode 200
          result.responseJson should be(ujson.read(s"""
                                                      |{
                                                      |  "type":"USER_WITH_GROUPS",
                                                      |  "correlation_id":"$correlationId",
                                                      |  "groups":[
                                                      |    {
                                                      |      "group":{"id":"group5","name":"Group 5"},
                                                      |      "username":"user4",
                                                      |      "kibana":{
                                                      |        "access":"unrestricted",
                                                      |        "index":"user4_group5_kibana_index"
                                                      |      }
                                                      |    },
                                                      |    {
                                                      |      "group":{"id":"group6","name":"Group 6"},
                                                      |      "username":"user4",
                                                      |      "kibana":{
                                                      |        "access":"unrestricted",
                                                      |        "index":"user4_group6_kibana_index",
                                                      |        "template_index":"user4_group6_kibana_template_index"
                                                      |      }
                                                      |    }
                                                      |  ]
                                                      |}
                                                      |""".stripMargin))
        }
        "broad multi-group block with no explicit index defers to specific per-group blocks" in {
          val correlationId = UUID.randomUUID().toString
          val userMetadataManager = new RorApiManager(
            basicAuthClientWithRorMetadataAttached(
              "user7",
              "pass",
              ("x-ror-correlation-id", correlationId),
              ("x-ror-kbn-license-type", "ent")
            ),
            esVersionUsed
          )

          val result = userMetadataManager.fetchUserMetadata()

          result should have statusCode 200
          result.responseJson should be(ujson.read(s"""
                                                      |{
                                                      |  "type":"USER_WITH_GROUPS",
                                                      |  "correlation_id":"$correlationId",
                                                      |  "groups":[
                                                      |    {
                                                      |      "group":{"id":"group10","name":"group10"},
                                                      |      "username":"user7",
                                                      |      "kibana":{
                                                      |        "access":"unrestricted",
                                                      |        "index":".kibana"
                                                      |      }
                                                      |    },
                                                      |    {
                                                      |      "group":{"id":"group11","name":"group11"},
                                                      |      "username":"user7",
                                                      |      "kibana":{
                                                      |        "access":"rw",
                                                      |        "index":"user7_group11_kibana_index"
                                                      |      }
                                                      |    },
                                                      |    {
                                                      |      "group":{"id":"group12","name":"group12"},
                                                      |      "username":"user7",
                                                      |      "kibana":{
                                                      |        "access":"rw",
                                                      |        "index":"user7_group12_kibana_index"
                                                      |      }
                                                      |    }
                                                      |  ]
                                                      |}
                                                      |""".stripMargin))
        }
        "wildcard groups block resolves @{acl:current_group} per group" in {
          val correlationId = UUID.randomUUID().toString
          val userMetadataManager = new RorApiManager(
            basicAuthClientWithRorMetadataAttached(
              "user8",
              "pass",
              ("x-ror-correlation-id", correlationId),
              ("x-ror-kbn-license-type", "ent")
            ),
            esVersionUsed
          )

          val result = userMetadataManager.fetchUserMetadata()

          result should have statusCode 200
          result.responseJson should be(ujson.read(s"""
                                                      |{
                                                      |  "type":"USER_WITH_GROUPS",
                                                      |  "correlation_id":"$correlationId",
                                                      |  "groups":[
                                                      |    {
                                                      |      "group":{"id":"group13","name":"group13"},
                                                      |      "username":"user8",
                                                      |      "kibana":{
                                                      |        "access":"unrestricted",
                                                      |        "index":"user8_group13_kibana_index",
                                                      |        "hidden_apps":["user8_app1"]
                                                      |      }
                                                      |    },
                                                      |    {
                                                      |      "group":{"id":"group14","name":"group14"},
                                                      |      "username":"user8",
                                                      |      "kibana":{
                                                      |        "access":"unrestricted",
                                                      |        "index":"user8_group14_kibana_index",
                                                      |        "hidden_apps":["user8_app1"]
                                                      |      }
                                                      |    },
                                                      |    {
                                                      |      "group":{"id":"group15","name":"group15"},
                                                      |      "username":"user8",
                                                      |      "kibana":{
                                                      |        "access":"unrestricted",
                                                      |        "index":"user8_group15_kibana_index",
                                                      |        "hidden_apps":["user8_app1"]
                                                      |      }
                                                      |    }
                                                      |  ]
                                                      |}
                                                      |""".stripMargin))
        }
        "at least one block is matched" in {
          val correlationId = UUID.randomUUID().toString

          val userMetadataManager = new RorApiManager(
            basicAuthClientWithRorMetadataAttached(
              "user2",
              "pass",
              ("x-ror-correlation-id", correlationId),
              ("x-ror-kbn-license-type", "ent")
            ),
            esVersionUsed
          )

          val result = userMetadataManager.fetchUserMetadata()

          result should have statusCode 200
          result.responseJson should be(
            ujson.read(s"""
                          |{
                          |  "type":"USER_WITH_GROUPS",
                          |  "correlation_id":"$correlationId",
                          |  "groups":[
                          |    {
                          |      "group":{"id":"group2","name":"group2"},
                          |      "username":"user2",
                          |      "kibana":{
                          |        "access":"api_only",
                          |        "index":"user2_kibana_index",
                          |        "hidden_apps":["user2_app1","user2_app2","/^Analytics\\\\|(?!(Maps)$$).*$$/"],
                          |        "allowed_api_paths":[
                          |          {"http_method":"ANY","path_regex":"^/api/spaces/.*$$"},
                          |          {"http_method":"GET","path_regex":"^/api/spaces\\\\?test\\\\=12\\\\.2$$"}
                          |        ],
                          |        "metadata":{"e":{"f":1},"a":1,"b":true,"c":"text","d":["a","b"]}
                          |      }
                          |    }
                          |  ]
                          |}
                          |""".stripMargin)
          )
        }
        "block with no available groups collected is matched" in {
          val correlationId = UUID.randomUUID().toString
          val userMetadataManager = new RorApiManager(
            basicAuthClientWithRorMetadataAttached(
              "user3",
              "pass",
              ("x-ror-correlation-id", correlationId),
              ("x-ror-kbn-license-type", "ent")
            ),
            esVersionUsed
          )

          val result = userMetadataManager.fetchUserMetadata()

          result should have statusCode 200
          result.responseJson should be(ujson.read(s"""
                                                      |{
                                                      |  "type":"USER_WITHOUT_GROUPS",
                                                      |  "correlation_id":"$correlationId",
                                                      |  "username":"user3",
                                                      |  "kibana":{
                                                      |    "access":"unrestricted",
                                                      |    "index":"user3_kibana_index",
                                                      |    "hidden_apps":["user3_app1","user3_app2"]
                                                      |  }
                                                      |}
                                                      |""".stripMargin))
        }
      }
      "return forbidden" when {
        "no block is matched" in {
          val correlationId = UUID.randomUUID().toString
          val userMetadataManager = new RorApiManager(
            basicAuthClientWithRorMetadataAttached(
              "userXXX",
              "pass",
              ("x-ror-correlation-id", correlationId),
              ("x-ror-kbn-license-type", "ent")
            ),
            esVersionUsed
          )

          val result = userMetadataManager.fetchUserMetadata()

          result should have statusCode 403
        }
        "forbid block is matched" in {
          val userMetadataManager = new RorApiManager(
            basicAuthClientWithRorMetadataAttached("user5", "pass", ("x-ror-kbn-license-type", "ent")),
            esVersionUsed
          )

          val result = userMetadataManager.fetchUserMetadata("ent")

          result should have statusCode 403
          result.responseJson should be(
            ujson.read(
              s"""
                 |{
                 |  "error":{
                 |    "root_cause":[
                 |      {
                 |        "type":"forbidden_response",
                 |        "reason":"you are unauthorized to access this resource",
                 |        "due_to":"FORBIDDEN_BY_BLOCK"
                 |      }
                 |    ],
                 |    "type":"forbidden_response",
                 |    "reason":"you are unauthorized to access this resource",
                 |    "due_to":"FORBIDDEN_BY_BLOCK"
                 |  },
                 |  "status":403
                 |}
                 |""".stripMargin
            )
          )
        }
      }
    }
  }

}
