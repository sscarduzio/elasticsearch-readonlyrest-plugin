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

import com.dimafeng.testcontainers.ForAllTestContainer
import org.junit.Assert.assertEquals
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.utils.containers.{ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.RorApiManager
import ujson.Str


class CurrentUserMetadataTests extends WordSpec with ForAllTestContainer {

  override lazy val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/current_user_metadata/readonlyrest.yml"
  )

  "An ACL" when {
    "handling current user metadata kibana plugin request" should {
      "allow to proceed" when {
        "several blocks are matched" in {
          val user1MetadataManager = new RorApiManager(container.nodesContainers.head.client("user1", "pass"))

          val result = user1MetadataManager.fetchMetadata()

          assertEquals(200, result.responseCode)
          result.responseJson.obj.size should be (3)
          result.responseJson("x-ror-username").str should be("user1")
          result.responseJson("x-ror-current-group").str should be("group3")
          result.responseJson("x-ror-available-groups").arr.toList should be(List(Str("group3"), Str("group1")))
        }
        "several blocks are matched and current group is set" in {
          val user1MetadataManager = new RorApiManager(container.nodesContainers.head.client("user4", "pass"))

          val result = user1MetadataManager.fetchMetadata("group6")

          assertEquals(200, result.responseCode)
          result.responseJson.obj.size should be (5)
          result.responseJson("x-ror-username").str should be("user4")
          result.responseJson("x-ror-current-group").str should be("group6")
          result.responseJson("x-ror-available-groups").arr.toList should be(List(Str("group5"), Str("group6")))
          result.responseJson("x-ror-kibana_index").str should be("user4_group6_kibana_index")
          result.responseJson("x-ror-kibana_template_index").str should be("user4_group6_kibana_template_index")
        }
        "at least one block is matched" in {
          val user2MetadataManager = new RorApiManager(container.nodesContainers.head.client("user2", "pass"))

          val result = user2MetadataManager.fetchMetadata()

          assertEquals(200, result.responseCode)
          result.responseJson.obj.size should be (6)
          result.responseJson("x-ror-username").str should be("user2")
          result.responseJson("x-ror-current-group").str should be("group2")
          result.responseJson("x-ror-available-groups").arr.toList  should be(List(Str("group2")))
          result.responseJson("x-ror-kibana_index").str should be("user2_kibana_index")
          result.responseJson("x-ror-kibana-hidden-apps").arr.toList should be(List(Str("user2_app1"), Str("user2_app2")))
          result.responseJson("x-ror-kibana_access").str should be("ro")
        }
        "block with no available groups collected is matched" in {
          val user3MetadataManager = new RorApiManager(container.nodesContainers.head.client("user3", "pass"))

          val result = user3MetadataManager.fetchMetadata()

          assertEquals(200, result.responseCode)
          result.responseJson.obj.size should be (3)
          result.responseJson("x-ror-username").str should be("user3")
          result.responseJson("x-ror-kibana_index").str should be("user3_kibana_index")
          result.responseJson("x-ror-kibana-hidden-apps").arr.toList should be(List(Str("user3_app1"), Str("user3_app2")))
        }
      }
      "return forbidden" when {
        "no block is matched" in {
          val unknownUserMetadataManager = new RorApiManager(container.nodesContainers.head.client("userXXX", "pass"))

          val result = unknownUserMetadataManager.fetchMetadata()

          assertEquals(401, result.responseCode)
        }
        "current group is set but it doesn't exist on available groups list" in {
          val user4MetadataManager = new RorApiManager(container.nodesContainers.head.client("user4", "pass"))

          val result = user4MetadataManager.fetchMetadata("group7")

          assertEquals(401, result.responseCode)
        }
        "block with no available groups collected is matched and current group is set" in {
          val user3MetadataManager = new RorApiManager(container.nodesContainers.head.client("user3", "pass"))

          val result = user3MetadataManager.fetchMetadata("group7")

          assertEquals(401, result.responseCode)
        }
      }
    }
  }
}