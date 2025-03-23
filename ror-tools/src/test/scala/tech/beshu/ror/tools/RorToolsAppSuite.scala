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

import better.files.File as BetterFile
import monix.execution.Scheduler
import org.apache.commons.compress.archivers.tar.TarFile
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.must.Matchers.include
import org.scalatest.matchers.should.Matchers.{equal, should, shouldNot}
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.tools.RorToolsAppHandler.Result
import tech.beshu.ror.tools.core.utils.InOut
import tech.beshu.ror.tools.utils.{CapturingOutputAndMockingInput, DirectoryUtils, TestEsContainerManager}
import tech.beshu.ror.utils.containers.*

import java.io.{Console as _, *}
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.language.postfixOps

class RorToolsAppSuite extends AnyWordSpec with BeforeAndAfterAll {

  implicit val scheduler: Scheduler = Scheduler.computation(10)

  private val tempDirectory = BetterFile.newTemporaryDirectory()
  private val localPath = tempDirectory.path
  private val esLocalPath = (tempDirectory / "es").path

  // Before performing tests in this suite:
  // - the ES container is started (using security variant RorWithXpackSecurity)
  // - but the ROR plugin is not installed on container creation/start
  // - the `/usr/share/elasticsearch` is compressed and downloaded from the container
  // - the tar file is stored in the /temp_directory_for_ror_tools_app_suite directory in resources
  // - on each test this file is uncompressed and the fresh copy of the ES directory is used
  override protected def beforeAll(): Unit = {
    withTestEsContainer { esContainer =>
      esContainer.execInContainer("tar", "-cvf", "/tmp/elasticsearch.tar", "-C", "/usr/share/elasticsearch", "modules", "bin", "lib", "plugins", "tmp")
      esContainer.copyFileFromContainer("/tmp/elasticsearch.tar", s"$localPath/elasticsearch.tar")
    }
    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    tempDirectory.clear()
  }

  "ROR tools app" should {
    "Patching is successful for ES installation that was not patched (with consent given in arg)" in withFreshEsDirectory { () =>
      val (result, output) = captureResultAndOutput {
        RorToolsAppHandler.handle(Array("patch", "--I_UNDERSTAND_AND_ACCEPT_ES_PATCHING", "yes", "--es-path", esLocalPath.toString))(_)
      }
      result should equal(Result.Success)
      output should include(
        """Checking if Elasticsearch is patched ...
          |Creating backup ...
          |Patching ...
          |Elasticsearch is patched! ReadonlyREST is ready to use"""
          .stripMargin
      )
    }
    "Patching is successful for ES installation that was not patched (with consent given in interactive mode)" in withFreshEsDirectory { () =>
      val (result, output) = captureResultAndOutputWithInteraction(
        RorToolsAppHandler.handle(Array("patch", "--es-path", esLocalPath.toString))(_),
        response = "yes"
      )
      result should equal(Result.Success)
      output should include(
        """Elasticsearch needs to be patched to work with ReadonlyREST. You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.
          |Do you understand the implications of ES patching? (yes/no): Checking if Elasticsearch is patched ...
          |Creating backup ...
          |Patching ...
          |Elasticsearch is patched! ReadonlyREST is ready to use
          |""".stripMargin
      )
    }
    "Patching does not start when user declines to accept implications of patching (in arg)" in withFreshEsDirectory { () =>
      val (result, output) = captureResultAndOutput {
        RorToolsAppHandler.handle(Array("patch", "--I_UNDERSTAND_AND_ACCEPT_ES_PATCHING", "no"))(_)
      }
      result should equal(Result.Failure(1))
      output should equal(
        """You have to confirm, that You understand the implications of ES patching in order to perform it.
          |You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.
          |""".stripMargin
      )
    }
    "Patching does not start when user declines to accept implications of patching (in interactive mode)" in withFreshEsDirectory { () =>
      val (result, output) = captureResultAndOutputWithInteraction(
        RorToolsAppHandler.handle(Array("patch"))(_),
        response = "no"
      )
      result should equal(Result.Failure(1))
      output should equal(
        """Elasticsearch needs to be patched to work with ReadonlyREST. You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.
          |Do you understand the implications of ES patching? (yes/no): You have to confirm, that You understand the implications of ES patching in order to perform it.
          |You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.
          |""".stripMargin
      )
    }
    "Patching not started because of not existing directory" in withFreshEsDirectory { () =>
      val (result, output) = captureResultAndOutput {
        RorToolsAppHandler.handle(Array("patch", "--I_UNDERSTAND_AND_ACCEPT_ES_PATCHING", "yes", "--es-path", "/wrong_directory"))(_)
      }
      result should equal(Result.CommandNotParsed)
      output should include(
        """Error: Path [/wrong_directory] does not exist
          |Try --help for more information.""".stripMargin
      )
    }
    "Successfully patch, verify and unpatch" in withFreshEsDirectory { () =>
      // Patch
      val hashBeforePatching = DirectoryUtils.calculateHash(esLocalPath)
      val (patchResult, patchOutput) = captureResultAndOutput {
        RorToolsAppHandler.handle(Array("patch", "--I_UNDERSTAND_AND_ACCEPT_ES_PATCHING", "yes", "--es-path", esLocalPath.toString))(_)
      }
      patchResult should equal(Result.Success)
      patchOutput should include(
        """Checking if Elasticsearch is patched ...
          |Creating backup ...
          |Patching ...
          |Elasticsearch is patched! ReadonlyREST is ready to use"""
          .stripMargin
      )
      val hashAfterPatching = DirectoryUtils.calculateHash(esLocalPath)

      // Verify
      val (verifyResult, verifyOutput) = captureResultAndOutput {
        RorToolsAppHandler.handle(Array("verify", "--es-path", esLocalPath.toString))(_)
      }

      verifyResult should equal(Result.Success)
      verifyOutput should include(
        """Checking if Elasticsearch is patched ...
          |Elasticsearch is patched! ReadonlyREST can be used"""
          .stripMargin
      )

      // Unpatch
      val hashBeforeUnpatching = DirectoryUtils.calculateHash(esLocalPath)
      val (unpatchResult, unpatchOutput) = captureResultAndOutput {
        RorToolsAppHandler.handle(Array("unpatch", "--es-path", esLocalPath.toString))(_)
      }
      unpatchResult should equal(Result.Success)
      unpatchOutput should include(
        """Checking if Elasticsearch is patched ...
          |Restoring ...
          |Elasticsearch is unpatched! ReadonlyREST can be removed now"""
          .stripMargin
      )
      val hashAfterUnpatching = DirectoryUtils.calculateHash(esLocalPath)

      hashBeforePatching should equal(hashAfterUnpatching)
      hashAfterPatching should equal(hashBeforeUnpatching)
      hashBeforePatching shouldNot equal(hashAfterPatching)
    }
  }

  private def captureResultAndOutput(block: InOut => Result): (Result, String) = {
    val inOut = new CapturingOutputAndMockingInput()
    val result = block(inOut)
    (result, inOut.getOutputBuffer)
  }

  private def captureResultAndOutputWithInteraction(block: InOut => Result, response: String): (Result, String) = {
    val inOut = new CapturingOutputAndMockingInput(Some(response))
    val result = block(inOut)
    (result, inOut.getOutputBuffer)
  }

  private def withTestEsContainer(withStartedEs: EsContainer => Unit): Unit = {
    val manager = new TestEsContainerManager
    try {
      manager.start()
        .map(_ => withStartedEs(manager.esContainer))
        .runSyncUnsafe(5 minutes)
    } finally {
      manager.stop().runSyncUnsafe()
    }
  }

  private def unTar(tarPath: Path, outputPath: Path): Unit = {
    val tarFile = new TarFile(tarPath.toFile)
    for (entry <- tarFile.getEntries.asScala) {
      val path = outputPath.resolve(entry.getName)
      if (entry.isDirectory) {
        Files.createDirectories(path)
      } else {
        Files.createDirectories(path.getParent)
        Files.copy(tarFile.getInputStream(entry), path)
      }
    }
  }

  private def withFreshEsDirectory(perform: () => Unit): Unit = {
    try {
      DirectoryUtils.clean(esLocalPath)
      File(s"$esLocalPath").mkdirs
      unTar(Path.of(s"$localPath/elasticsearch.tar"), Path.of(s"$esLocalPath"))
      File(s"$esLocalPath/plugins/readonlyrest/").mkdirs
      Files.copy(
        File(s"$esLocalPath/tmp/plugin-descriptor.properties").toPath,
        File(s"$esLocalPath/plugins/readonlyrest/plugin-descriptor.properties").toPath,
      )
      Files.copy(
        File(s"$esLocalPath/tmp/plugin-security.policy").toPath,
        File(s"$esLocalPath/plugins/readonlyrest/plugin-security.policy").toPath,
      )
      perform()
    } finally {
      DirectoryUtils.clean(esLocalPath)
    }
  }

}
