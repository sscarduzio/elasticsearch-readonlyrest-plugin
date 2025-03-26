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

import better.files.File
import monix.execution.Scheduler
import org.scalatest.matchers.must.Matchers.include
import org.scalatest.matchers.should.Matchers.{equal, should, shouldNot}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import tech.beshu.ror.tools.RorTools.Result
import tech.beshu.ror.tools.core.utils.InOut
import tech.beshu.ror.tools.utils.{CapturingOutputAndMockingInput, ExampleEsWithRorContainer}
import tech.beshu.ror.utils.files.FileUtils

import java.nio.file.Path
import scala.language.postfixOps

class RorToolsAppSuite extends AnyWordSpec with BeforeAndAfterAll with BeforeAndAfterEach {

  implicit val scheduler: Scheduler = Scheduler.computation(10)

  private val tempDirectory = File.newTemporaryDirectory()
  private val localPath = tempDirectory.path
  private val esLocalPath = (tempDirectory / "es").path

  private val esContainer = new ExampleEsWithRorContainer

  private object RorToolsTestApp extends RorTools

  override protected def beforeAll(): Unit = {
    prepareElasticsearchAndRorBinaries()
    super.beforeAll()
  }

  override protected def beforeEach(): Unit = {
    prepareDirectoryWithElasticsearchAndRorBinariesForTest()
    super.beforeEach()
  }

  "ROR tools app" should {
    "Patching is successful for ES installation that was not patched (with consent given in arg)" in {
      val (result, output) = captureResultAndOutput {
        RorToolsTestApp.run(Array("patch", "--I_UNDERSTAND_AND_ACCEPT_ES_PATCHING", "yes", "--es-path", esLocalPath.toString))(_)
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
    "Patching is successful for ES installation that was not patched (with consent given in interactive mode)" in {
      val (result, output) = captureResultAndOutputWithInteraction(
        RorToolsTestApp.run(Array("patch", "--es-path", esLocalPath.toString))(_),
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
    "Patching does not start when user declines to accept implications of patching (in arg)" in {
      val (result, output) = captureResultAndOutput {
        RorToolsTestApp.run(Array("patch", "--I_UNDERSTAND_AND_ACCEPT_ES_PATCHING", "no"))(_)
      }
      result should equal(Result.Failure)
      output should equal(
        """You have to confirm, that You understand the implications of ES patching in order to perform it.
          |You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.
          |""".stripMargin
      )
    }
    "Patching does not start when user declines to accept implications of patching (in interactive mode)" in {
      val (result, output) = captureResultAndOutputWithInteraction(
        RorToolsTestApp.run(Array("patch"))(_),
        response = "no"
      )
      result should equal(Result.Failure)
      output should equal(
        """Elasticsearch needs to be patched to work with ReadonlyREST. You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.
          |Do you understand the implications of ES patching? (yes/no): You have to confirm, that You understand the implications of ES patching in order to perform it.
          |You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.
          |""".stripMargin
      )
    }
    "Patching not started because of not existing directory" in {
      val (result, output) = captureResultAndOutput {
        RorToolsTestApp.run(Array("patch", "--I_UNDERSTAND_AND_ACCEPT_ES_PATCHING", "yes", "--es-path", "/wrong_directory"))(_)
      }
      result should equal(Result.CommandNotParsed)
      output should include(
        """Error: Path [/wrong_directory] does not exist
          |ROR tools 1.0.0
          |Usage: java -jar ror-tools.jar [patch|unpatch|verify] [options]
          |
          |Command: patch [options]
          |patch is a command that modifies ES installation for ROR purposes
          |  --es-path <value>        Path to elasticsearch directory; default=/usr/share/elasticsearch
          |
          |  --i_understand_and_accept_es_patching <yes/no>
          |                           Optional, when provided with value 'yes', it confirms that the user understands and accepts the implications of ES patching. The patching can therefore be performed. When not provided, user will be asked for confirmation in interactive mode. You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.
          |Command: unpatch [options]
          |unpatch is a command that reverts modifications done by patching
          |  --es-path <value>        Path to elasticsearch directory; default=/usr/share/elasticsearch
          |
          |Command: verify [options]
          |verify is a command that verifies if ES installation is patched
          |  --es-path <value>        Path to elasticsearch directory; default=/usr/share/elasticsearch
          |
          |  -h, --help               prints this usage text""".stripMargin
      )
    }
    "Successfully patch, verify and unpatch" in {
      // Patch
      val hashBeforePatching = FileUtils.calculateHash(esLocalPath)
      val (patchResult, patchOutput) = captureResultAndOutput {
        RorToolsTestApp.run(Array("patch", "--I_UNDERSTAND_AND_ACCEPT_ES_PATCHING", "yes", "--es-path", esLocalPath.toString))(_)
      }
      patchResult should equal(Result.Success)
      patchOutput should include(
        """Checking if Elasticsearch is patched ...
          |Creating backup ...
          |Patching ...
          |Elasticsearch is patched! ReadonlyREST is ready to use"""
          .stripMargin
      )
      val hashAfterPatching = FileUtils.calculateHash(esLocalPath)

      // Verify
      val (verifyResult, verifyOutput) = captureResultAndOutput {
        RorToolsTestApp.run(Array("verify", "--es-path", esLocalPath.toString))(_)
      }

      verifyResult should equal(Result.Success)
      verifyOutput should include(
        """Checking if Elasticsearch is patched ...
          |Elasticsearch is patched! ReadonlyREST can be used"""
          .stripMargin
      )

      // Unpatch
      val hashBeforeUnpatching = FileUtils.calculateHash(esLocalPath)
      val (unpatchResult, unpatchOutput) = captureResultAndOutput {
        RorToolsTestApp.run(Array("unpatch", "--es-path", esLocalPath.toString))(_)
      }
      unpatchResult should equal(Result.Success)
      unpatchOutput should include(
        """Checking if Elasticsearch is patched ...
          |Restoring ...
          |Elasticsearch is unpatched! ReadonlyREST can be removed now"""
          .stripMargin
      )
      val hashAfterUnpatching = FileUtils.calculateHash(esLocalPath)

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

  override protected def afterEach(): Unit = {
    super.afterEach()
    File(esLocalPath).delete(swallowIOExceptions = true)
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    tempDirectory.clear()
  }

  // This method handles downloading necessary files from ES container:
  // - the ES container is started (using security variant RorWithXpackSecurity)
  // - but the ROR plugin is not installed on container creation/start
  // - the `/usr/share/elasticsearch` is compressed and downloaded from the container
  // - the tar file is stored in the temporary directory
  // - on each test this file is uncompressed and the fresh copy of the ES directory is used
  private def prepareElasticsearchAndRorBinaries(): Unit = {
    esContainer.withTestEsContainer { esContainer =>
      esContainer.execInContainer("tar", "-cvf", "/tmp/elasticsearch.tar", "-C", "/usr/share/elasticsearch", "modules", "bin", "lib", "plugins", "tmp")
      esContainer.copyFileFromContainer("/tmp/elasticsearch.tar", s"$localPath/elasticsearch.tar")
    }
  }

  // This method handles preparing fresh ES directory for test:
  // - clean temporary directory
  // - create /es in temporary directory
  // - untar elasticsearch.tar prepared when beforeAll is executed
  private def prepareDirectoryWithElasticsearchAndRorBinariesForTest(): Unit = {
    File(esLocalPath).delete(swallowIOExceptions = true)
    File(s"$esLocalPath").createDirectory()
    FileUtils.unTar(Path.of(s"$localPath/elasticsearch.tar"), Path.of(s"$esLocalPath"))
  }

}
