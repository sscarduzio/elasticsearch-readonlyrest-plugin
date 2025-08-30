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
package tech.beshu.ror.tools

import cats.data.NonEmptyList
import com.github.dockerjava.api.DockerClient
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.atomic.AtomicInt
import org.scalatest.matchers.must.Matchers.include
import org.scalatest.matchers.should.Matchers.{should, shouldNot}
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.DockerClientFactory
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.*
import tech.beshu.ror.utils.containers.EsContainer.EsContainerImplementation
import tech.beshu.ror.utils.containers.EsContainerCreator.EsNodeSettings
import tech.beshu.ror.utils.containers.images.Elasticsearch.EsInstallationType
import tech.beshu.ror.utils.containers.images.ReadonlyRestWithEnabledXpackSecurityPlugin
import tech.beshu.ror.utils.containers.logs.DockerLogsToStringConsumer
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.SearchManager
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.{EsModulePatterns, OsUtils}

import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.util.Try

// There is a change introduced in Elasticsearch since versions 9.0.1 and 8.18.1 (older ES versions are not affected)
// The change: https://github.com/elastic/elasticsearch/pull/126852 ("With this PR we restrict the paths we allow access to, forbidding plugins to specify/request entitlements for reading or writing to specific protected directories.")
// In our use case it causes problems with apt-based installations of ES and patching:
//   - ROR cannot check (on startup) whether the ES is patched
//   - that is because after the aforementioned change in ES, the ROR plugin cannot access the /usr/share/elasticsearch directory
//   - we bypass this problem (ROR plugin cannot check the patch status, so it just allows to continue starting ES, with warning in logs)
// This test suite verifies, that both official ES image and apt-based ES installation with ROR can start. Logs are also asserted to detect the warning.
class PatchingOfAptBasedEsInstallationSuite extends AnyWordSpec with ESVersionSupportForAnyWordSpecLike {

  import PatchingOfAptBasedEsInstallationSuite.*

  implicit val scheduler: Scheduler = Scheduler.computation(10)

  private val validRorConfigFile = "/basic/readonlyrest.yml"

  "ES" when {
    if (OsUtils.isWindows) {
      "using native Windows ES" should {
        // In ES versions 7.x, 8.0.x - 8.17.x the ROR security policy grants permission:
        // `permission java.io.FilePermission "/usr/share/elasticsearch", "read";`
        // It is not a valid Windows path, so ror-tools patcher cannot read the ES directory and prints warning.
        "ES {7.x, 8.0.x - 8.17.x} successfully load ROR plugin and start (with warning about not being able to verify patch)" excludeES(allEs6x, allEs9x, allEs818x) in {
          val dockerLogs = withTestEsContainerManager(EsInstallationType.NativeWindowsProcess) { esContainer =>
            testRorStartup(usingManager = esContainer)
          }
          dockerLogs should include("ReadonlyREST is waiting for full Elasticsearch init")
          dockerLogs should include("Elasticsearch fully initiated. ReadonlyREST can continue ...")
          dockerLogs should include("Loading Elasticsearch settings from file:")
          dockerLogs should include("Cannot verify if the ES was patched")
          dockerLogs should include("ReadonlyREST was loaded")
        }
        // In ES versions 8.18.x, 9.x the ROR security policy grants permission in the newer syntax:
        // ALL-UNNAMED:
        //  - files:
        //      - relative_path: ../
        // It is valid on Windows, so ror-tools patcher can read the ES directory and does not print the warning.
        "ES {8.18.x, 9.x} successfully load ROR plugin and start (with warning about not being able to verify patch)" excludeES(allEs6x, allEs7x, allEs8xBelowEs818x) in {
          val dockerLogs = withTestEsContainerManager(EsInstallationType.NativeWindowsProcess) { esContainer =>
            testRorStartup(usingManager = esContainer)
          }
          dockerLogs should include("ReadonlyREST is waiting for full Elasticsearch init")
          dockerLogs should include("Elasticsearch fully initiated. ReadonlyREST can continue ...")
          dockerLogs should include("Loading Elasticsearch settings from file:")
          dockerLogs shouldNot include("Cannot verify if the ES was patched")
          dockerLogs should include("ReadonlyREST was loaded")
        }
      }
    } else {
      "using official ES image" should {
        "successfully load ROR plugin and start (patch verification without warning)" in {
          val dockerLogs = withTestEsContainerManager(EsInstallationType.EsDockerImage) { esContainer =>
            testRorStartup(usingManager = esContainer)
          }
          dockerLogs should include("ReadonlyREST is waiting for full Elasticsearch init")
          dockerLogs should include("Elasticsearch fully initiated. ReadonlyREST can continue ...")
          dockerLogs should include("Loading Elasticsearch settings from file:")
          dockerLogs shouldNot include("Cannot verify if the ES was patched")
          dockerLogs should include("ReadonlyREST was loaded")
        }
      }
      "installed on Ubuntu using apt" should {
        // ES 6.x is not available as apt package, so we do not test it
        "ES {7.x, 8.0.x - 8.17.x} successfully load ROR plugin and start (without warning about not being able to verify patch)" excludeES(allEs6x, allEs9x, allEs818x) in {
          val dockerLogs = withTestEsContainerManager(EsInstallationType.UbuntuDockerImageWithEsFromApt) { esContainer =>
            testRorStartup(usingManager = esContainer)
          }
          dockerLogs should include("ReadonlyREST is waiting for full Elasticsearch init")
          dockerLogs should include("Elasticsearch fully initiated. ReadonlyREST can continue ...")
          dockerLogs should include("Loading Elasticsearch settings from file:")
          dockerLogs shouldNot include("Cannot verify if the ES was patched")
          dockerLogs should include("ReadonlyREST was loaded")
        }
        "ES {8.18.x, 9.x} successfully load ROR plugin and start (with warning about not being able to verify patch)" excludeES(allEs6x, allEs7x, allEs8xBelowEs818x) in {
          val dockerLogs = withTestEsContainerManager(EsInstallationType.UbuntuDockerImageWithEsFromApt) { esContainer =>
            testRorStartup(usingManager = esContainer)
          }
          dockerLogs should include("ReadonlyREST is waiting for full Elasticsearch init")
          dockerLogs should include("Elasticsearch fully initiated. ReadonlyREST can continue ...")
          dockerLogs should include("Loading Elasticsearch settings from file:")
          dockerLogs should include("Cannot verify if the ES was patched. component [readonlyrest], module [ALL-UNNAMED], class [class tech.beshu.ror.tools.core.utils.EsDirectory$], entitlement [file], operation [read], path [/usr/share/elasticsearch]")
          dockerLogs should include("ReadonlyREST was loaded")
        }
      }
    }
  }

  private def withTestEsContainerManager(esInstallationType: EsInstallationType)
                                        (testCode: TestEsContainerManager => Task[Unit]): String = {
    val esContainer = new TestEsContainerManager(validRorConfigFile, esInstallationType)
    try {
      (for {
        _ <- esContainer.start()
        _ <- testCode(esContainer)
      } yield ()).runSyncUnsafe(5 minutes)
      esContainer.getLogs
    } finally {
      esContainer.stop().runSyncUnsafe()
    }
  }

  private def testRorStartup(usingManager: TestEsContainerManager): Task[Unit] = {
    for {
      restClient <- usingManager.createRestClient
      searchTestResults <- searchTest(restClient)
      result <- handleResult(searchTestResults)
    } yield result
  }

  private def searchTest(client: RestClient): Task[TestResponse] = Task.delay {
    val manager = new SearchManager(client, esVersionUsed)
    val response = manager.searchAll("*")
    TestResponse(response.responseCode, response.responseJson)
  }

  private def handleResult(result: TestResponse): Task[Unit] = {
    val hasEsRespondedWithSuccess = result.responseCode == 200
    if (hasEsRespondedWithSuccess) {
      Task.unit
    } else {
      Task.raiseError(new IllegalStateException(s"Test failed. Expected success response but was: [$result]"))
    }
  }
}

private object PatchingOfAptBasedEsInstallationSuite extends EsModulePatterns {
  final case class TestResponse(responseCode: Int, responseJson: JSON)

  private val uniqueClusterId: AtomicInt = AtomicInt(1)

  final class TestEsContainerManager(rorConfigFile: String, esInstallationType: EsInstallationType) extends EsContainerCreator {

    private lazy val dockerClient: DockerClient = DockerClientFactory.instance().client()

    private val dockerLogsCollector = new DockerLogsToStringConsumer

    private val esContainer = createEsContainer

    def start(): Task[Unit] = Task.delay(esContainer.start())

    def stop(): Task[Unit] = for {
      _ <- Task.delay(esContainer.stop())
      _ <- Task.delay(esContainer.removeImage())
    } yield ()

    def getLogs: String = dockerLogsCollector.getLogs

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
              rorConfigFileName = rorConfigFile
            )
          ),
          containerSpecification = ContainerSpecification.empty,
          esVersion = EsVersion.DeclaredInProject
        ),
        allNodeNames = NonEmptyList.of(nodeName),
        nodeDataInitializer = NoOpElasticsearchNodeDataInitializer,
        startedClusterDependencies = StartedClusterDependencies(List.empty),
        esInstallationType = esInstallationType,
        additionalLogConsumer = Some(dockerLogsCollector)
      )
    }
  }
}
