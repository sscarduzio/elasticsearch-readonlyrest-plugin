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

import cats.data.NonEmptyList
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.atomic.AtomicInt
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.*
import tech.beshu.ror.utils.containers.ElasticsearchNodeWaitingStrategy.AwaitingReadyStrategy.WaitForEsRestApiResponsive
import tech.beshu.ror.utils.containers.EsContainerCreator.EsNodeSettings
import tech.beshu.ror.utils.containers.images.ReadonlyRestWithEnabledXpackSecurityPlugin
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.SearchManager
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.EsModulePatterns

import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.util.Try

class RorStartingSuite extends AnyWordSpec with ESVersionSupportForAnyWordSpecLike {

  import RorStartingSuite.*

  implicit val scheduler: Scheduler = Scheduler.computation(10)

  private val validRorConfigFile = "/basic/readonlyrest.yml"

  private val notStartedResponseCodeKey = "readonlyrest.not_started_response_code"

  "ES" when {
    "ROR does not started yet" should {
      "return not started response with http code 403" when {
        "403 configured" in withTestEsContainerManager(Map(notStartedResponseCodeKey -> "403")) { esContainer =>
          testRorStartup(usingManager = esContainer, expectedResponseCode = 403)
        }
        "no option configured" in withTestEsContainerManager(Map.empty) { esContainer =>
          testRorStartup(usingManager = esContainer, expectedResponseCode = 403)
        }
      }
      "return not started response with http code 503" when {
        "503 configured" in withTestEsContainerManager(Map(notStartedResponseCodeKey -> "503")) { esContainer =>
          testRorStartup(usingManager = esContainer, expectedResponseCode = 503)
        }
      }
    }
  }

  private def withTestEsContainerManager(additionalEsYamlEntries: Map[String, String])
                                        (testCode: TestEsContainerManager => Task[Unit]): Unit = {
    val esContainer = new TestEsContainerManager(
      rorConfigFile = validRorConfigFile,
      additionalEsYamlEntries = additionalEsYamlEntries
    )
    esContainer.start().runSyncUnsafe(5 minutes)
    try {
      testCode(esContainer)
    } finally {
      esContainer.stop().runSyncUnsafe()
    }
  }

  private def testRorStartup(usingManager: TestEsContainerManager, expectedResponseCode: Int): Task[Unit] = {
    for {
      restClient <- usingManager.createRestClient
      searchTestResult <- searchTest(client = restClient)
      result <- handleResult(searchTestResult, expectedResponseCode)
    } yield result
  }

  private def searchTest(client: RestClient): Task[TestResponse] = Task.delay {
    val searchManager = new SearchManager(client, esVersionUsed)
    val response = searchManager.searchAll("*")
    TestResponse(response.responseCode, response.responseJson)
  }

  private def handleResult(result: TestResponse, expectedResponseCode: Int): Task[Unit] = {
    val isResponseCodeOk = result.responseCode == expectedResponseCode
    val isResponseErrorOk = result
      .responseJson("error")
      .obj("root_cause")
      .arr.exists { json =>
        json("reason").str == "Forbidden by ReadonlyREST" &&
          json("due_to").str == "READONLYREST_NOT_READY_YET"
      }
    if (isResponseCodeOk && isResponseErrorOk) {
      Task.unit
    } else {
      Task.raiseError(new IllegalStateException(s"Test failed. Expected success response and ROR failed to start response but was: [$result]"))
    }
  }
}

private object RorStartingSuite extends EsModulePatterns {
  final case class TestResponse(responseCode: Int, responseJson: JSON)

  private val uniqueClusterId: AtomicInt = AtomicInt(1)

  final class TestEsContainerManager(rorConfigFile: String,
                                     additionalEsYamlEntries: Map[String, String]) extends EsContainerCreator {

    private val esContainer = createEsContainer

    def start(): Task[Unit] = Task.delay(esContainer.start())

    def stop(): Task[Unit] = Task.delay(esContainer.stop())

    def createRestClient: Task[RestClient] = {
      Task.tailRecM(()) { _ =>
        Task.delay(createAdminClient)
      }
    }

    private def createAdminClient = {
      Try(esContainer.adminClient)
        .toEither
        .left.map(_ => ())
    }

    private def createEsContainer: EsContainer = {
      val clusterName = s"ROR_${uniqueClusterId.getAndIncrement()}"
      val nodeName = s"${clusterName}_1"
      create(
        nodeSettings = EsNodeSettings(
          nodeName = nodeName,
          clusterName = clusterName,
          securityType = SecurityType.RorWithXpackSecurity(
            ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes.default.copy(
              rorConfigFileName = rorConfigFile,
              rorInIndexConfigLoadingDelay = 5 seconds
            )
          ),
          containerSpecification = ContainerSpecification.empty.copy(
            additionalElasticsearchYamlEntries = additionalEsYamlEntries
          ),
          esVersion = EsVersion.DeclaredInProject
        ),
        allNodeNames = NonEmptyList.of(nodeName),
        nodeDataInitializer = NoOpElasticsearchNodeDataInitializer,
        startedClusterDependencies = StartedClusterDependencies(List.empty),
        awaitingReadyStrategy = WaitForEsRestApiResponsive
      )
    }
  }
}
