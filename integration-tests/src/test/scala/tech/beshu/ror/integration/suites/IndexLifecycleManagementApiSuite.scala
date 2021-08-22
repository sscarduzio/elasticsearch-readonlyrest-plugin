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
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.IndexLifecycleManagementApiSuite.{ExamplePolicies, PolicyGenerator}
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.{ClusterManager, DocumentManager, IndexLifecycleManager, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Version

trait IndexLifecycleManagementApiSuite
  extends AnyWordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport
    with ESVersionSupport
    with BeforeAndAfterEach
    with Matchers
    with Eventually {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/index_lifecycle_management_api/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      numberOfInstances = 2,
      nodeDataInitializer = IndexLifecycleManagementApiSuite.nodeDataInitializer(),
      xPackSupport = true
    )
  )

  private lazy val adminIndexManager = new IndexManager(adminClient, esVersionUsed)
  private lazy val adminIndexLifecycleManager = new IndexLifecycleManager(adminClient)
  private lazy val dev1IndexLifecycleManager = new IndexLifecycleManager(basicAuthClient("dev1", "test"))
  private lazy val dev3IndexLifecycleManager = new IndexLifecycleManager(basicAuthClient("dev3", "test"))

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(200, Millis)))

  "Policy management APIs" when {
    "create lifecycle operation is used" should {
      "be handled" excludeES (allEs5x, allEs6xBelowEs66x) in {
        val response = dev1IndexLifecycleManager.putPolicy(PolicyGenerator.next(), ExamplePolicies.forceMergePolicy)
        response.responseCode should be(200)
      }
    }
    "delete lifecycle operation is used" should {
      "be handled" excludeES (allEs5x, allEs6xBelowEs66x) in {
        val policy = PolicyGenerator.next()
        dev1IndexLifecycleManager.putPolicy(policy, ExamplePolicies.forceMergePolicy).force()

        val response = dev1IndexLifecycleManager.deletePolicy(policy)
        response.responseCode should be(200)
      }
    }
    "get lifecycle operation is used" should {
      "be handled" excludeES (allEs5x, allEs6xBelowEs66x) in {
        val policy = PolicyGenerator.next()
        dev1IndexLifecycleManager.putPolicy(policy, ExamplePolicies.forceMergePolicy).force()

        val response = dev1IndexLifecycleManager.getPolicy(policy)

        response.responseCode should be(200)
        response.policies.get(policy) should be(Some(ExamplePolicies.forceMergePolicy))
      }
    }
  }

  "Index management APIs" when {
    "move index to step operation is used" should {
      "be allowed" when {
        "user has access to requested index" excludeES (allEs5x, allEs6xBelowEs66x) in {
          val index = "dynamic1"
          createIndexWithAppliedMergedPolicy(index)

          val result = dev3IndexLifecycleManager.moveToLifecycleStep(
            index,
            currentStep = ujson.read(
              s"""
                 |{
                 |  "phase": "new",
                 |  "action": "complete",
                 |  "name": "complete"
                 |}
               """.stripMargin),
            nextStep = ujson.read(
              s"""
                 |{
                 |  "phase": "warm",
                 |  "action": "forcemerge",
                 |  "name": "forcemerge"
                 |}
               """.stripMargin
            )
          )

          result.responseCode should be(200)
        }
        "user has access to requested index (through configured wildcard)" excludeES (allEs5x, allEs6xBelowEs66x) in {
          val index = "dynamic_1"
          createIndexWithAppliedMergedPolicy(index)

          val result = dev3IndexLifecycleManager.moveToLifecycleStep(
            index,
            currentStep = ujson.read(
              s"""
                 |{
                 |  "phase": "new",
                 |  "action": "complete",
                 |  "name": "complete"
                 |}
               """.stripMargin),
            nextStep = ujson.read(
              s"""
                 |{
                 |  "phase": "warm",
                 |  "action": "forcemerge",
                 |  "name": "forcemerge"
                 |}
               """.stripMargin
            )
          )

          result.responseCode should be(200)
        }
        "no indices rule was used" excludeES (allEs5x, allEs6xBelowEs66x) in {
          val index = "dynamic_2"
          createIndexWithAppliedMergedPolicy(index)

          val result = adminIndexLifecycleManager.moveToLifecycleStep(
            index = "dynamic_2",
            currentStep = ujson.read(
              s"""
                 |{
                 |  "phase": "new",
                 |  "action": "complete",
                 |  "name": "complete"
                 |}
               """.stripMargin),
            nextStep = ujson.read(
              s"""
                 |{
                 |  "phase": "warm",
                 |  "action": "forcemerge",
                 |  "name": "forcemerge"
                 |}
               """.stripMargin
            )
          )

          result.responseCode should be(200)
        }
      }
      "be forbidden" when {
        "user has no access to requested index" excludeES (allEs5x, allEs6xBelowEs66x) in {
          val result = dev3IndexLifecycleManager.moveToLifecycleStep(
            index = "index2",
            currentStep = ujson.read(
              s"""
                 |{
                 |  "phase": "new",
                 |  "action": "complete",
                 |  "name": "complete"
                 |}
               """.stripMargin),
            nextStep = ujson.read(
              s"""
                 |{
                 |  "phase": "warm",
                 |  "action": "forcemerge",
                 |  "name": "forcemerge"
                 |}
               """.stripMargin
            )
          )

          result.responseCode should be(403)
        }
      }
    }
    "retry policy operation is used" should {
      "be allowed" when {
        "user has an access to the requested index" excludeES (allEs5x, allEs6xBelowEs66x) in {
          val index = "dynamic1"
          createIndexWithAppliedShrinkPolicyWhichCaseErrorStep(index)

          eventually {
            val result = dev3IndexLifecycleManager.retryPolicyExecution(index)

            result.responseCode should be(200)
          }
        }
        "user has and access to all requested indices" excludeES (allEs5x, allEs6xBelowEs66x) in {
          val index1 = "dynamic_1"
          createIndexWithAppliedShrinkPolicyWhichCaseErrorStep(index1)
          val index2 = "dynamic_2"
          createIndexWithAppliedShrinkPolicyWhichCaseErrorStep(index2)

          eventually {
            val result = dev3IndexLifecycleManager.retryPolicyExecution(index1, index2)

            result.responseCode should be(200)
          }
        }
      }
      "not be allowed" when {
        "user has no access to at least one of requested indices" excludeES (allEs5x, allEs6xBelowEs66x) in {
          val index1 = "dynamic1"
          createIndexWithAppliedShrinkPolicyWhichCaseErrorStep(index1)
          val index2 = "dynamic2"
          createIndexWithAppliedShrinkPolicyWhichCaseErrorStep(index2)

          eventually {
            val result = dev3IndexLifecycleManager.retryPolicyExecution(index1, index2)

            result.responseCode should be(403)
          }
        }
      }
    }
    "remove policy from index operation is used" should {
      "be allowed" when {
        "user has access to requested indices" excludeES (allEs5x, allEs6xBelowEs66x) in {
          val index1 = "dynamic1"
          createIndexWithAppliedMergedPolicy(index1)
          val index2 = "dynamic_1"
          createIndexWithAppliedMergedPolicy(index2)

          val result = dev3IndexLifecycleManager.removePolicyFromIndex(index1, index2)

          result.responseCode should be (200)
        }
        "user has access to requested index pattern" excludeES (allEs5x, allEs6xBelowEs66x) in {
          val index = "dynamic_1"
          createIndexWithAppliedMergedPolicy(index)

          val result = dev3IndexLifecycleManager.removePolicyFromIndex("dynamic_1*")

          result.responseCode should be (200)
        }
        "no indices rule was used" excludeES (allEs5x, allEs6xBelowEs66x) in {
          val index = "dynamic_1"
          createIndexWithAppliedMergedPolicy(index)

          val result = adminIndexLifecycleManager.removePolicyFromIndex("*")

          result.responseCode should be (200)
        }
      }
      "not be allowed" when {
        "user has no access to at least one of requested indices" excludeES (allEs5x, allEs6xBelowEs66x) in {
          val index1 = "dynamic1"
          createIndexWithAppliedMergedPolicy(index1)
          val index2 = "dynamic2"
          createIndexWithAppliedMergedPolicy(index2)

          val result = dev3IndexLifecycleManager.removePolicyFromIndex(index1, index2)

          result.responseCode should be (403)
        }
        "user has no access to requested index pattern" excludeES (allEs5x, allEs6xBelowEs66x) in {
          val index = "dynamic1"
          createIndexWithAppliedMergedPolicy(index)

          val result = dev3IndexLifecycleManager.removePolicyFromIndex("dynamic*")

          result.responseCode should be (403)
        }
      }
    }
  }

  "Operation management APIs" when {
    "start ILM operation is used" should {
      "be handled" excludeES (allEs5x, allEs6xBelowEs66x) in {
        val response = dev1IndexLifecycleManager.startIlm()
        response.responseCode should be(200)
      }
    }
    "stop ILM operation is used" should {
      "be handled" excludeES (allEs5x, allEs6xBelowEs66x) in {
        val response = dev1IndexLifecycleManager.stopIlm()
        response.responseCode should be(200)
      }
    }
    "ILM status operation is used" should {
      "be handled" excludeES (allEs5x, allEs6xBelowEs66x) in {
        val response = dev1IndexLifecycleManager.ilmStatus()
        response.responseCode should be(200)
      }
    }
    "explain operation is used" should {
      "be allowed" when {
        "user has access to all requested indices" excludeES (allEs5x, allEs6xBelowEs66x) in {
          val response = dev1IndexLifecycleManager.ilmExplain("index1", "index1_1")

          response.responseCode should be(200)
          response.indices.keys.toSet should be(Set("index1", "index1_1"))
        }
        "user has access to at least one requested index" when {
          "full name index was used" excludeES (allEs5x, allEs6xBelowEs66x) in {
            val response = dev1IndexLifecycleManager.ilmExplain("index1", "index2")

            response.responseCode should be(200)
            response.indices.keys.toSet should be(Set("index1"))
          }
          "index with wildcard is used (no need to narrow the pattern)" excludeES (allEs5x, allEs6xBelowEs66x) in {
            val response = dev1IndexLifecycleManager.ilmExplain("index1_*", "index2_*")

            response.responseCode should be(200)
            response.indices.keys.toSet should be(Set("index1_1", "index1_2"))
          }
          "index with wildcard is used (the pattern is narrowed)" excludeES (allEs5x, allEs6xBelowEs66x) in {
            val response = dev1IndexLifecycleManager.ilmExplain("index1*", "index2*")

            response.responseCode should be(200)
            response.indices.keys.toSet should be(Set("index1", "index1_1", "index1_2"))
          }
          "all indices are requested" excludeES (allEs5x, allEs6xBelowEs66x) in {
            val response = dev1IndexLifecycleManager.ilmExplain("_all")

            response.responseCode should be(200)
            response.indices.keys.toSet should be(Set("index1", "index1_1", "index1_2"))
          }
        }
        "no indices rule was used" excludeES (allEs5x, allEs6xBelowEs66x) in {
          val response = adminIndexLifecycleManager.ilmExplain("*")

          response.responseCode should be(200)
          response.indices.keys.toSet should contain allOf ("index1", "index1_1", "index1_2", "index2", "index2_1")
        }
      }
      "return empty result" when {
        "user has no access to requested index" when {
          "index name with wildcard is used" excludeES (allEs5x, allEs6xBelowEs66x) in {
            val response = dev1IndexLifecycleManager.ilmExplain("index2*")

            response.responseCode should be(200)
            response.indices.keys.toSet should be(Set.empty)
          }
        }
      }
      "pretend that index doesn't exist" when {
        "user has no access to requested index" when {
          "full name index was used" excludeES (allEs5x, allEs6xBelowEs66x) in {
            val response = dev1IndexLifecycleManager.ilmExplain("index2")

            response.responseCode should be(404)
          }
        }
      }
    }
  }

  private def createIndexWithAppliedMergedPolicy(index: String): Unit = {
    val policy = PolicyGenerator.next()
    adminIndexLifecycleManager.putPolicy(policy, ExamplePolicies.forceMergePolicy).force()
    adminIndexManager.createIndex(index).force()
    adminIndexManager
      .putSettings(
        indexName = index,
        settings = ujson.read(
          s"""
             |{
             |  "index": {
             |    "lifecycle": {
             |      "name": "$policy"
             |    }
             |  }
             |}
                """.stripMargin)
      )
      .force()
  }

  private def createIndexWithAppliedShrinkPolicyWhichCaseErrorStep(index: String): Unit = {
    val policy = PolicyGenerator.next()
    adminIndexLifecycleManager.putPolicy(policy, ExamplePolicies.shrinkPolicy).force()
    adminIndexManager
      .createIndex(
        index,
        settings = Some(ujson.read {
          s"""
             |{
             |  "settings": {
             |    "index.number_of_shards": 1,
             |    "index.lifecycle.name": "$policy"
             |  }
             |}
          """.stripMargin
        })
      )
      .force()
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    adminIndexManager.removeIndex("dynamic*").force()
  }
}

object IndexLifecycleManagementApiSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val document = ujson.read(s"""{"test": "abc"}""")
    documentManager.createDoc("index1", "1", document).force()
    documentManager.createDoc("index1_1", "1", document).force()
    documentManager.createDoc("index1_2", "1", document).force()
    documentManager.createDoc("index2", "1", document).force()
    documentManager.createDoc("index2_1", "1", document).force()

    if(Version.greaterOrEqualThan(esVersion, 6, 6, 0)) {
      val clusterManager = new ClusterManager(adminRestClient, esVersion)
      clusterManager
        .putSettings(ujson.read {
          s"""
             |{
             |  "persistent" : {
             |    "indices.lifecycle.poll_interval": "100ms"
             |  }
             |}
       """.stripMargin
        })
        .force()
    }
  }

  private object PolicyGenerator {
    private val uniquePart = Atomic(0)

    def next(): String = s"Policy-${uniquePart.incrementAndGet()}"
  }

  private object ExamplePolicies {
    val forceMergePolicy: JSON = ujson.read {
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

    val shrinkPolicy: JSON = ujson.read {
      """
        |{
        |  "policy": {
        |    "phases": {
        |      "warm": {
        |        "min_age": "100 ms",
        |        "actions": {
        |          "shrink": {
        |            "number_of_shards": 4
        |          }
        |        }
        |      }
        |    }
        |  }
        |}
      """.stripMargin
    }
  }
}