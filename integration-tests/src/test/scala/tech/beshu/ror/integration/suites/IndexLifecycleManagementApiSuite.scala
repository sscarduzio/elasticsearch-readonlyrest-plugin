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

import monix.execution.atomic.Atomic
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}
import tech.beshu.ror.integration.suites.IndexLifecycleManagementApiSuite.{ExamplePolicies, PolicyGenerator}
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexLifecycleManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait IndexLifecycleManagementApiSuite
  extends WordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport
    with ESVersionSupport
    with XpackSupport
    with BeforeAndAfterEach
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/index_lifecycle_management_api/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = IndexLifecycleManagementApiSuite.nodeDataInitializer(),
      xPackSupport = true
    )
  )

  private lazy val adminIndexLifecycleManager = new IndexLifecycleManager(adminClient)
  private lazy val dev1IndexLifecycleManager = new IndexLifecycleManager(basicAuthClient("dev1", "test"))

  "Policy management APIs" when {
    "create lifecycle operation is used" should {
      "be handled" in {
        val response = dev1IndexLifecycleManager.putPolicy(PolicyGenerator.next(), ExamplePolicies.policy1)
        response.responseCode should be(200)
      }
    }
    "delete lifecycle operation is used" should {
      "be handled" in {
        val policy = PolicyGenerator.next()
        dev1IndexLifecycleManager.putPolicy(policy, ExamplePolicies.policy1).force()

        val response = dev1IndexLifecycleManager.deletePolicy(policy)
        response.responseCode should be(200)
      }
    }
    "get lifecycle operation is used" should {
      "be handled" in {
        val policy = PolicyGenerator.next()
        dev1IndexLifecycleManager.putPolicy(policy, ExamplePolicies.policy1).force()

        val response = dev1IndexLifecycleManager.getPolicy(policy)

        response.responseCode should be(200)
        response.policies.get(policy) should be(Some(ExamplePolicies.policy1))
      }
    }
  }

  "Operation management APIs" when {
    "start ILM operation is used" should {
      "be handled" in {
        val response = dev1IndexLifecycleManager.startIlm()
        response.responseCode should be(200)
      }
    }
    "stop ILM operation is used" should {
      "be handled" in {
        val response = dev1IndexLifecycleManager.stopIlm()
        response.responseCode should be(200)
      }
    }
    "ILM status operation is used" should {
      "be handled" in {
        val response = dev1IndexLifecycleManager.ilmStatus()
        response.responseCode should be(200)
      }
    }
    "explain operation is used" should {
      "be allowed" when {
        "user has access to all requested indices" in {
          val response = dev1IndexLifecycleManager.ilmExplain("index1", "index1_1")

          response.responseCode should be (200)
          response.indices.keys.toSet should be (Set("index1", "index1_1"))
        }
        "user has access to at least one requested index" when {
          "full name index was used" in {
            val response = dev1IndexLifecycleManager.ilmExplain("index1", "index2")

            response.responseCode should be (200)
            response.indices.keys.toSet should be (Set("index1"))
          }
          "index with wildcard is used (no need to narrow the pattern)" in {
            val response = dev1IndexLifecycleManager.ilmExplain("index1_*", "index2_*")

            response.responseCode should be (200)
            response.indices.keys.toSet should be (Set("index1_1", "index1_2"))
          }
          "index with wildcard is used (the pattern is narrowed)" in {
            val response = dev1IndexLifecycleManager.ilmExplain("index1*", "index2*")

            response.responseCode should be (200)
            response.indices.keys.toSet should be (Set("index1", "index1_1", "index1_2"))
          }
          "all indices are requested" in {
            val response = dev1IndexLifecycleManager.ilmExplain("_all")

            response.responseCode should be (200)
            response.indices.keys.toSet should be (Set("index1", "index1_1", "index1_2"))
          }
        }
        "no indices rule was used" in {
          val response = adminIndexLifecycleManager.ilmExplain("*")

          response.responseCode should be (200)
          response.indices.keys.toSet should be (Set("index1", "index1_1", "index1_2", "index2", "index2_1"))
        }
      }
      "return empty result" when {
        "user has no access to requested index" when {
          "index name with wildcard is used" in {
            val response = dev1IndexLifecycleManager.ilmExplain("index2*")

            response.responseCode should be(200)
            response.indices.keys.toSet should be (Set.empty)
          }
        }
      }
      "pretend that index doesn't exist" when {
        "user has no access to requested index" when {
          "full name index was used" in {
            val response = dev1IndexLifecycleManager.ilmExplain("index2")

            response.responseCode should be (404)
          }
        }
      }
    }
  }
}

object IndexLifecycleManagementApiSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val document = ujson.read(s"""{"test": "abc"}""")
    documentManager.createDoc("index1", "1", document)
    documentManager.createDoc("index1_1", "1", document)
    documentManager.createDoc("index1_2", "1", document)
    documentManager.createDoc("index2", "1", document)
    documentManager.createDoc("index2_1", "1", document)
  }

  private object PolicyGenerator {
    private val uniquePart = Atomic(0)

    def next(): String = s"Policy-${uniquePart.incrementAndGet()}"
  }

  private object ExamplePolicies {
    val policy1: JSON = ujson.read {
      """
        |{
        |  "policy": {
        |    "phases": {
        |      "warm": {
        |        "min_age": "10d",
        |        "actions": {
        |          "forcemerge": {
        |            "max_num_segments": 1
        |           }
        |        }
        |      }
        |    }
        |  }
        |}
      """.stripMargin
    }
  }
}