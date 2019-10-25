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
import tech.beshu.ror.utils.elasticsearch.ClusterStateManager

class UriRegexRuleTests extends WordSpec with ForAllTestContainer {

  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/uri_regex_rules/readonlyrest.yml"
  )

  "A uri rule" should {
    "allow health check" when {
      "configured single pattern matches requested uri" in {
        assertRuleMatchForUser("user1")
      }

      "one of configured patterns matches requested uri" in {
        assertRuleMatchForUser("user3")
      }

      "one of configured patterns resolves based on defined group of user" in {
        assertRuleMatchForUser("user5")
      }
    }

    "not allow health check" when {
      "configured single pattern does not match requested uri" in {
        assertRuleDoesNotMatchForUser("user2")
      }

      "none one of configured patterns matches requested uri" in {
        assertRuleDoesNotMatchForUser("user4")
      }
    }
  }

  private def assertRuleMatchForUser(name: String): Unit = assertHealthCheckStatus(200, name)

  private def assertRuleDoesNotMatchForUser(name: String): Unit = assertHealthCheckStatus(401, name)

  private def assertHealthCheckStatus(status: Int, name: String): Unit = {
    val manager = new ClusterStateManager(container.nodesContainers.head.client(name, "pass"))
    val result = manager.healthCheck()
    assertEquals(status, result.responseCode)
  }
}