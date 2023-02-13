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
import tech.beshu.ror.utils.containers.EsContainerCreator.EsNodeSettings
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.containers.images.ReadonlyRestPlugin.Config.Attributes
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.{ClusterManager, SearchManager}
import tech.beshu.ror.utils.gradle.RorPluginGradleProject
import tech.beshu.ror.utils.httpclient.RestClient

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

trait RorStartingSuite extends AnyWordSpec with EsContainerCreator {

  import RorStartingSuite._

  implicit val scheduler: Scheduler = Scheduler.computation(10)

  private val validRorConfigFile = "/basic/readonlyrest.yml"
  private val atomicInt: AtomicInt = AtomicInt(1)

  private val notStartedResponseCodeKey = "readonlyrest.not_started_response_code"

  "ES" when {
    "ROR does not started yet" should {
      "return not started response with http code 403" when {
        "403 configured" in {
          val esContainer = createEsContainer(
            rorConfigFile = validRorConfigFile,
            additionalEsYamlEntries = Map(notStartedResponseCodeKey -> "403")
          )

          notStartedYetTestScenario(esContainer = esContainer, expectedResponseCode = 403)
            .runSyncUnsafe(2 minutes)
        }
        "no option configured" in {
          val esContainer = createEsContainer(rorConfigFile = validRorConfigFile, additionalEsYamlEntries = Map.empty)

          notStartedYetTestScenario(esContainer = esContainer, expectedResponseCode = 403)
            .runSyncUnsafe(2 minutes)
        }
        "failed to load ROR ACL" in {
          val esContainer = createEsContainer(
            rorConfigFile = validRorConfigFile,
            additionalEsYamlEntries = Map(notStartedResponseCodeKey -> "200") // unsupported response code
          )

          notStartedYetTestScenario(esContainer = esContainer, expectedResponseCode = 403)
            .runSyncUnsafe(2 minutes)
        }
      }
      "return not started response with http code 503" when {
        "503 configured" in {
          val esContainer = createEsContainer(
            rorConfigFile = validRorConfigFile,
            additionalEsYamlEntries = Map(notStartedResponseCodeKey -> "503")
          )

          notStartedYetTestScenario(esContainer = esContainer, expectedResponseCode = 503)
            .runSyncUnsafe(2 minutes)
        }
      }
    }
  }

  private def notStartedYetTestScenario(esContainer: EsContainer, expectedResponseCode: Int) = {
    Task
      .gatherUnordered(
        List(
          startContainer(esContainer),
          testTrafficAndStopContainer(esContainer, expectedResponseCode)
        )
      )
  }

  private def testTrafficAndStopContainer(esContainer: EsContainer, expectedResponseCode: Int): Task[Unit] = {
    for {
      restClient <- createRestClient(esContainer)
      searchTestResults <- searchTest(client = restClient, searchAttemptsCount = 20000)
      _ <- stopContainer(esContainer)
      result <- handleResults(searchTestResults, expectedResponseCode)
    } yield result
  }

  private def searchTest(client: RestClient, searchAttemptsCount: Int): Task[Seq[TestResponse]] = Task.delay {
    val sm = new SearchManager(client)
    Range.inclusive(1, searchAttemptsCount).flatMap { _ =>
      Try(sm.searchAll("*")) match {
        case Failure(_) =>
          None
        case Success(response) =>
          Some(TestResponse(response.responseCode, response.responseJson))
      }
    }
      .distinct
  }

  private def handleResults(results: Seq[TestResponse], expectedResponseCode: Int): Task[Unit] = {
    val hasEsRespondedWithNotStartedResponse = results
      .filter { response =>
        response.responseCode == expectedResponseCode
      }
      .exists { response =>
        response
          .responseJson("error")
          .obj("root_cause")
          .arr.exists { json =>
          json("reason").str == "forbidden" &&
            json("due_to").str == "READONLYREST_NOT_READY_YET"
        }
      }

    val hasEsRespondedWithSuccess = results.exists(_.responseCode == 200)

    if (hasEsRespondedWithNotStartedResponse && hasEsRespondedWithSuccess) {
      Task.unit
    } else {
      Task.raiseError(new IllegalStateException(s"Test failed. Expected success response and ROR failed to start response but was: [$results]"))
    }
  }

  private def createEsContainer(rorConfigFile: String,
                                additionalEsYamlEntries: Map[String, String]): EsContainer = {
    val clusterName = s"ROR_${atomicInt.getAndIncrement()}"
    val nodeName = s"${clusterName}_1"
    create(
      mode = Mode.Plugin,
      nodeSettings = EsNodeSettings(
        nodeName = nodeName,
        clusterName = clusterName,
        securityType = SecurityType.RorSecurity(Attributes.default.copy(
          rorConfigFileName = rorConfigFile
        )),
        containerSpecification = ContainerSpecification.empty.copy(
          additionalElasticsearchYamlEntries = additionalEsYamlEntries
        ),
        esVersion = EsVersion.DeclaredInProject
      ),
      allNodeNames = NonEmptyList.of(nodeName),
      nodeDataInitializer = NoOpElasticsearchNodeDataInitializer,
      startedClusterDependencies = StartedClusterDependencies(List.empty)
    )
  }

  private def startContainer(container: EsContainer) = Task.delay(container.start())

  private def stopContainer(container: EsContainer) = {
    Task.delay(container.stop())
  }

  private def createRestClient(container: EsContainer): Task[RestClient] = {
    Task.tailRecM(()) { _ =>
      Task.delay(createAdminClient(container))
    }
  }

  private def createAdminClient(container: EsContainer) = {
    Try(container.adminClient)
      .toEither
      .left.map(_ => ())
  }
}

object RorStartingSuite {
  final case class TestResponse(responseCode: Int, responseJson: JSON)
}
