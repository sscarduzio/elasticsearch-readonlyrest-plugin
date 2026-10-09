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
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.must.Matchers.{be, include}
import org.scalatest.matchers.should.Matchers.{should, shouldNot}
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.Container.ExecResult
import org.testcontainers.containers.ExecConfig
import tech.beshu.ror.integration.suites.base.support.HeavySuiteGated
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.*
import tech.beshu.ror.utils.containers.EsContainerCreator.EsNodeSettings
import tech.beshu.ror.utils.containers.images.Elasticsearch.EsInstallationType
import tech.beshu.ror.utils.containers.images.ReadonlyRestWithEnabledXpackSecurityPlugin
import tech.beshu.ror.utils.containers.logs.DockerLogsToStringConsumer
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.SearchManager
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.OsUtils.CurrentOs
import tech.beshu.ror.utils.misc.{EsModule, EsModulePatterns, OsUtils}

import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.util.Try
import scala.util.matching.Regex

// There is a change introduced in Elasticsearch since versions 9.0.1 and 8.18.1 (older ES versions are not affected)
// The change: https://github.com/elastic/elasticsearch/pull/126852 ("With this PR we restrict the paths we allow access to, forbidding plugins to specify/request entitlements for reading or writing to specific protected directories.")
// In our use case it causes problems with apt-based installations of ES and patching:
//   - ROR cannot check (on startup) whether the ES is patched
//   - that is because after the aforementioned change in ES, the ROR plugin cannot access the /usr/share/elasticsearch directory
//   - we bypass this problem (ROR plugin cannot check the patch status, so it just allows to continue starting ES, with warning in logs)
// This test suite verifies, that both official ES image and apt-based ES installation with ROR can start. Logs are also asserted to detect the warning.
class PatchingOfAptBasedEsInstallationSuite
    extends AnyWordSpec
    with ESVersionSupportForAnyWordSpecLike
    with BeforeAndAfterAll
    with HeavySuiteGated {

  import PatchingOfAptBasedEsInstallationSuite.*

  implicit val scheduler: Scheduler = Scheduler.computation(10)

  private val validRorConfigFile = "/basic/readonlyrest.yml"

  // ES 6.x is not available as apt package, so we do not test it. The node set above and the tag of
  // the apt test below read this one value.
  private lazy val esVersionsWithoutAptPackage: Regex = allEs6x

  // The Linux nodes share nothing, so the suite boots them at once and each test reads only the logs
  // of its own node. Started one after the other, the suite pays two docker builds and two ES boots
  // in sequence. Windows keeps one node per test: the native ES install of a node name owns a fixed
  // port, and only one of the two Windows tests runs for a given ES version anyway.
  private val linuxNodes: Map[EsInstallationType, EsNode] = OsUtils.currentOs match {
    case CurrentOs.Windows =>
      Map.empty
    case CurrentOs.OtherThanWindows =>
      val installationTypes =
        if (EsModule.isCurrentModuleNotExcluded(esVersionsWithoutAptPackage))
          EsInstallationType.EsDockerImage :: EsInstallationType.UbuntuDockerImageWithEsFromApt :: Nil
        else
          EsInstallationType.EsDockerImage :: Nil

      installationTypes.map { installationType => installationType -> new EsNode(installationType) }.toMap
  }

  OsUtils.currentOs match {
    case CurrentOs.Windows =>
      "ES" when {
        "using native Windows ES" should {
          // In ES versions 7.x, 8.0.x - 8.17.x the ROR security policy grants permission:
          // `permission java.io.FilePermission "/usr/share/elasticsearch", "read";`
          // It is not a valid Windows path, so ror-tools patcher cannot read the ES directory and prints warning.
          "ES {7.x, 8.0.x - 8.17.x} successfully load ROR plugin and start (with warning about not being able to verify patch)" excludeES (
            allEs6x,
            allEs9x,
            allEs818x
          ) in {
            val dockerLogs = withTestEsContainerManager(EsInstallationType.NativeWindowsProcess) { esContainer =>
              testRorStartup(usingManager = esContainer)
            }
            dockerLogs should include("ReadonlyREST is waiting for full Elasticsearch init")
            dockerLogs should include("Elasticsearch fully initiated. ReadonlyREST can continue ...")
            dockerLogs should include("Loading ReadonlyREST main settings from file")
            dockerLogs should include("Cannot verify if the ES was patched")
            dockerLogs should include("ReadonlyREST was loaded")
          }
          // In ES versions 8.18.x, 9.x the ROR security policy grants permission in the newer syntax:
          // ALL-UNNAMED:
          //  - files:
          //      - relative_path: ../
          // It is valid on Windows, so ror-tools patcher can read the ES directory and does not print the warning.
          "ES {8.18.x, 9.x} successfully load ROR plugin and start (with warning about not being able to verify patch)" excludeES (
            allEs6x,
            allEs7x,
            allEs8xBelowEs818x
          ) in {
            val dockerLogs = withTestEsContainerManager(EsInstallationType.NativeWindowsProcess) { esContainer =>
              testRorStartup(usingManager = esContainer)
            }
            dockerLogs should include("ReadonlyREST is waiting for full Elasticsearch init")
            dockerLogs should include("Elasticsearch fully initiated. ReadonlyREST can continue ...")
            dockerLogs should include("Loading ReadonlyREST main settings from file")
            dockerLogs shouldNot include("Cannot verify if the ES was patched")
            dockerLogs should include("ReadonlyREST was loaded")
          }
        }
      }
    case CurrentOs.OtherThanWindows =>
      "ES" when {
        "using official ES image" should {
          "successfully load ROR plugin and start (patch verification without warning)" in {
            val dockerLogs = dockerLogsOf(EsInstallationType.EsDockerImage)
            dockerLogs should include("ReadonlyREST is waiting for full Elasticsearch init")
            dockerLogs should include("Elasticsearch fully initiated. ReadonlyREST can continue ...")
            dockerLogs should include("Loading ReadonlyREST main settings from file")
            dockerLogs shouldNot include("Cannot verify if the ES was patched")
            dockerLogs should include("ReadonlyREST was loaded")
          }
          // CI runs the ror-tools tests as root, so this test checks the unreadable metadata file as the ES user
          "report the unreadable patch metadata file, when the ES user cannot read the patch backup folder" in {
            val esNode = linuxNodeOf(EsInstallationType.EsDockerImage)
            val originalOwnerAndMode = esNode.execAsRoot(s"stat -c '%u:%g %a' $patchBackupFolder").trim
            val (originalOwner, originalMode) = originalOwnerAndMode.split(' ') match {
              case Array(owner, mode) => (owner, mode)
              case _ => throw new IllegalStateException(s"Unexpected stat output: [$originalOwnerAndMode]")
            }
            esNode.execAsRoot(s"chown root:root $patchBackupFolder && chmod 700 $patchBackupFolder")
            val verifyResult =
              try esNode.execAsEsUser(rorToolsCommand("verify", esHome))
              finally
                esNode.execAsRoot(s"chown $originalOwner $patchBackupFolder && chmod $originalMode $patchBackupFolder")

            verifyResult.getExitCode shouldNot be(0)
            (verifyResult.getStdout + verifyResult.getStderr) should include(
              s"Cannot read the ROR patch metadata file $patchBackupFolder/patch_metadata"
            )
          }
          // CI runs the ror-tools tests as root, so this test checks the unreadable patched file as the ES user
          "report the unreadable patched file, when the ES user cannot read a jar that ROR patched" in {
            val esNode = linuxNodeOf(EsInstallationType.EsDockerImage)
            val patchedJar = s"$esHome/lib/elasticsearch-$esVersionUsed.jar"
            val originalMode = esNode.execAsRoot(s"stat -c '%a' $patchedJar").trim
            esNode.execAsRoot(s"chmod 000 $patchedJar")
            val verifyResult =
              try esNode.execAsEsUser(rorToolsCommand("verify", esHome))
              finally esNode.execAsRoot(s"chmod $originalMode $patchedJar")

            verifyResult.getExitCode shouldNot be(0)
            (verifyResult.getStdout + verifyResult.getStderr) should include(
              s"Cannot read the files that ROR patched: $patchedJar."
            )
          }
          // CI runs the ror-tools tests as root. Here the ES user owns the files, but it is not a member of their
          // group, so Linux does not let it set that group. The test uses a copy of ES, so the node stays patched.
          "unpatch and patch, when the user that owns the ES files is not a member of their group" in {
            val esNode = linuxNodeOf(EsInstallationType.EsDockerImage)
            esNode.execAsRoot(copyEsWithForeignGroupCommand)
            val (unpatchResult, patchResult, verifyResult) =
              try {
                (
                  esNode.execAsEsUser(rorToolsCommand("unpatch", esCopyHome)),
                  esNode.execAsEsUser(rorToolsCommand("patch --I_UNDERSTAND_AND_ACCEPT_ES_PATCHING=yes", esCopyHome)),
                  esNode.execAsEsUser(rorToolsCommand("verify", esCopyHome))
                )
              } finally esNode.execAsRoot(s"rm -rf $esCopyHome")

            withClue(unpatchResult.getStdout + unpatchResult.getStderr) {
              unpatchResult.getExitCode should be(0)
              unpatchResult.getStdout should include("Elasticsearch is unpatched! ReadonlyREST can be removed now")
              unpatchResult.getStderr should include("WARNING: Cannot set the group of")
            }
            withClue(patchResult.getStdout + patchResult.getStderr) {
              patchResult.getExitCode should be(0)
              patchResult.getStdout should include("Elasticsearch is patched! ReadonlyREST is ready to use")
              patchResult.getStderr should include("WARNING: Cannot set the group of")
            }
            withClue(verifyResult.getStdout + verifyResult.getStderr) {
              verifyResult.getExitCode should be(0)
            }
          }
        }
        "installed on Ubuntu using apt" should {
          "ES successfully load ROR plugin and start (without warning about not being able to verify patch)" excludeES esVersionsWithoutAptPackage in {
            val dockerLogs = dockerLogsOf(EsInstallationType.UbuntuDockerImageWithEsFromApt)
            dockerLogs should include("ReadonlyREST is waiting for full Elasticsearch init")
            dockerLogs should include("Elasticsearch fully initiated. ReadonlyREST can continue ...")
            dockerLogs should include("Loading ReadonlyREST main settings from file")
            dockerLogs shouldNot include("Cannot verify if the ES was patched")
            dockerLogs should include("ReadonlyREST was loaded")
          }
        }
      }
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    Task.parTraverseUnordered(linuxNodes.values.toList)(_.start).runSyncUnsafe(15 minutes)
  }

  override protected def afterAll(): Unit = {
    try {
      Task.parTraverseUnordered(linuxNodes.values.toList)(_.stop).runSyncUnsafe(5 minutes)
    } finally {
      super.afterAll()
    }
  }

  private def dockerLogsOf(esInstallationType: EsInstallationType): String = {
    linuxNodeOf(esInstallationType).dockerLogs
  }

  private def linuxNodeOf(esInstallationType: EsInstallationType): EsNode = {
    linuxNodes.getOrElse(
      esInstallationType,
      throw new IllegalStateException(s"No ES node was started for [$esInstallationType]")
    )
  }

  private final class EsNode(esInstallationType: EsInstallationType) {

    private val manager = new TestEsContainerManager(validRorConfigFile, esInstallationType)

    // Memoized, so `beforeAll` boots the node once and the test that reads the logs gets that one
    // result back. A node that does not start reports the failure to its own test only.
    private val startedNodeLogs: Task[String] =
      (for {
        _ <- manager.start().onErrorHandleWith(nodeDidNotStart)
        _ <- testRorStartup(usingManager = manager)
      } yield manager.getLogs).memoize

    private def nodeDidNotStart(error: Throwable): Task[Nothing] =
      Task.raiseError(
        new IllegalStateException(
          s"The ES node [$esInstallationType] did not start. Docker logs:\n${manager.getLogs}",
          error
        )
      )

    def start: Task[Unit] = startedNodeLogs.attempt.map(_ => ())

    // `beforeAll` has run this task to the end, so this reads its result back. The timeout only stops
    // a test that asks for a node the suite never started from hanging the run.
    def dockerLogs: String = startedNodeLogs.runSyncUnsafe(1 minute)

    def stop: Task[Unit] = manager.stop()

    // Fails the test when the node did not start, so the commands run only on a started node
    def execAsRoot(command: String): String = {
      dockerLogs
      val result = manager.execAsUser("root", command)
      if (result.getExitCode != 0) {
        throw new IllegalStateException(s"Command [$command] failed: ${result.getStdout}${result.getStderr}")
      }
      result.getStdout
    }

    def execAsEsUser(command: String): ExecResult = {
      dockerLogs
      manager.execAsUser("elasticsearch", command)
    }

  }

  private def withTestEsContainerManager(
      esInstallationType: EsInstallationType
  )(testCode: TestEsContainerManager => Task[Unit]): String = {
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

  private val esHome = "/usr/share/elasticsearch"
  private val patchBackupFolder = s"$esHome/plugins/readonlyrest/patch_backup"

  // A copy of the ES files that ror-tools reads or changes. The ES user owns the copy, and the copy has the group
  // `daemon`, which the ES user is not a member of. The ES 7.x+ images make the ES folders read-only (e.g. mode
  // 0555), and only root can write to them. So the copy gives the owner write permission, as a non-root patching needs.
  private val esCopyHome = "/tmp/es-with-foreign-group"

  private val copyEsWithForeignGroupCommand =
    s"rm -rf $esCopyHome && mkdir -p $esCopyHome/bin $esCopyHome/modules $esCopyHome/plugins && " +
      s"cp -a $esHome/lib $esCopyHome/ && cp -a $esHome/plugins/readonlyrest $esCopyHome/plugins/ && " +
      s"for module in x-pack-core x-pack-ilm x-pack-security transport-netty4; do " +
      s"if [ -d $esHome/modules/$$module ]; then cp -a $esHome/modules/$$module $esCopyHome/modules/; fi; done && " +
      s"chmod -R u+w $esCopyHome && chown -R elasticsearch:daemon $esCopyHome && " +
      s"! id -nG elasticsearch | grep -qw daemon"

  // ES 6.x images have no bundled JDK, so the command falls back to JAVA_HOME. ror-tools writes temporary files
  // to the working folder, so the command runs in the ES folder.
  private def rorToolsCommand(command: String, esPath: String) =
    s"""if [ -x $esHome/jdk/bin/java ]; then java=$esHome/jdk/bin/java; else java="$$JAVA_HOME/bin/java"; fi; """ +
      s"""cd $esPath && "$$java" -jar $esHome/plugins/readonlyrest/ror-tools.jar $command --es-path $esPath"""

  private val uniqueClusterId: AtomicInt = AtomicInt(1)

  final class TestEsContainerManager(rorConfigFile: String, esInstallationType: EsInstallationType)
      extends EsContainerCreator {

    private val dockerLogsCollector = new DockerLogsToStringConsumer

    private val esContainer = createEsContainer

    def start(): Task[Unit] = Task.delay(esContainer.start())

    def stop(): Task[Unit] = for {
      _ <- Task.delay(esContainer.stop())
      _ <- Task.delay(esContainer.removeImage())
    } yield ()

    def getLogs: String = dockerLogsCollector.getLogs

    def execAsUser(user: String, command: String): ExecResult =
      esContainer.container.execInContainer(
        ExecConfig.builder().user(user).command(Array("sh", "-c", command)).build()
      )

    def createRestClient: Task[RestClient] = {
      Task.tailRecM(()) { _ =>
        Task.delay(createAdminClient)
      }
    }

    private def createAdminClient = {
      Try(esContainer.adminClient).toEither.left.map(_ => ())
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
              rorSettingsFileName = rorConfigFile
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
