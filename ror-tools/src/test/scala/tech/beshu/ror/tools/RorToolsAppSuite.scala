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
import just.semver.SemVer
import monix.execution.Scheduler
import org.scalatest.matchers.must.Matchers.{be, include}
import org.scalatest.matchers.should.Matchers.{equal, should, shouldNot}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.tools.RorTools.Result
import tech.beshu.ror.tools.core.patches.base.EsPatchMetadataCodec
import tech.beshu.ror.tools.core.patches.internal.FilePatch.FilePatchMetadata
import tech.beshu.ror.tools.core.patches.internal.RorPluginDirectory.EsPatchMetadata
import tech.beshu.ror.tools.core.utils.InOut
import tech.beshu.ror.tools.utils.{CapturingOutputAndMockingInput, ExampleEsWithRorContainer}
import tech.beshu.ror.utils.files.FileUtils

import java.nio.file.Path
import scala.language.postfixOps

class RorToolsAppSuite
  extends AnyWordSpec
    with ESVersionSupportForAnyWordSpecLike
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  implicit val scheduler: Scheduler = Scheduler.computation(10)

  private val tempDirectory = File.newTemporaryDirectory()
  private val localPath = tempDirectory.path
  private val esDirectory = tempDirectory / "es"
  private val esLocalPath = esDirectory.path
  private val backupDirectory = esDirectory / "plugins" / "readonlyrest" / "patch_backup"
  private val patchMetadataFile = File((backupDirectory / "patch_metadata").path)

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
    "Patching successful for ES installation that was not patched (with consent given in arg)" in {
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
    "Patching successful for ES installation that was not patched (with consent given in arg in format with =)" in {
      val (result, output) = captureResultAndOutput {
        RorToolsTestApp.run(Array("patch", "--I_UNDERSTAND_AND_ACCEPT_ES_PATCHING=yes", "--es-path", esLocalPath.toString))(_)
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
    "Patching successful for ES installation that was not patched (with consent given in interactive mode)" in {
      val (result, output) = captureResultAndOutputWithInteraction(
        RorToolsTestApp.run(Array("patch", "--es-path", esLocalPath.toString))(_),
        response = Some("yes")
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
    "Patching successful first time, on second try not started because already patched" in {
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
      val (secondResult, secondOutput) = captureResultAndOutput {
        RorToolsTestApp.run(Array("patch", "--I_UNDERSTAND_AND_ACCEPT_ES_PATCHING", "yes", "--es-path", esLocalPath.toString))(_)
      }
      secondResult should equal(Result.Failure)
      secondOutput should include(
        """Checking if Elasticsearch is patched ...
          |ERROR: Elasticsearch is already patched with current version"""
          .stripMargin
      )
    }
    "Patching not started when user declines to accept implications of patching (in arg)" in {
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
    "Patching not started when user declines to accept implications of patching (in interactive mode)" in {
      val (result, output) = captureResultAndOutputWithInteraction(
        RorToolsTestApp.run(Array("patch"))(_),
        response = Some("no")
      )
      result should equal(Result.Failure)
      output should equal(
        """Elasticsearch needs to be patched to work with ReadonlyREST. You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.
          |Do you understand the implications of ES patching? (yes/no): You have to confirm, that You understand the implications of ES patching in order to perform it.
          |You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.
          |""".stripMargin
      )
    }
    "Patching not started when --I_UNDERSTAND_AND_ACCEPT_ES_PATCHING arg is not provided and console input is not possible" in {
      val (result, output) = captureResultAndOutputWithInteraction(
        RorToolsTestApp.run(Array("patch"))(_),
        response = None
      )
      result should equal(Result.Failure)
      output should equal(
        """|Elasticsearch needs to be patched to work with ReadonlyREST. You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.
           |Do you understand the implications of ES patching? (yes/no):""".stripMargin + " " +
          """|
             |It seems that the answer was not given or the ror-tools are executed in the environment that does not support console input.
             |Consider using silent mode and provide the answer using the parameter --I_UNDERSTAND_AND_ACCEPT_ES_PATCHING, read more in our documentation https://docs.readonlyrest.com/elasticsearch#id-5.-patch-elasticsearch.
             |""".stripMargin
      )
    }
    "Patching not started when --I_UNDERSTAND_AND_ACCEPT_ES_PATCHING value is empty" in {
      val (result, output) = captureResultAndOutputWithInteraction(
        RorToolsTestApp.run(Array("patch"))(_),
        response = Some("")
      )
      result should equal(Result.Failure)
      output should equal(
        """|Elasticsearch needs to be patched to work with ReadonlyREST. You can read about patching in our documentation: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch.
           |Do you understand the implications of ES patching? (yes/no):""".stripMargin + " " +
          """|
             |It seems that the answer was not given or the ror-tools are executed in the environment that does not support console input.
             |Consider using silent mode and provide the answer using the parameter --I_UNDERSTAND_AND_ACCEPT_ES_PATCHING, read more in our documentation https://docs.readonlyrest.com/elasticsearch#id-5.-patch-elasticsearch.
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
    "Patching not started because there is a metadata file indicating that the ES is already patched" in {
      givenPatchMetadata(
        rorVersionUsedForPatching = "0.0.1",
        esVersionThatWasPatched = esVersionUsed,
        patchedFilesMetadata = List.empty,
      )

      val (result, output) = captureResultAndOutput {
        RorToolsTestApp.run(Array("patch", "--I_UNDERSTAND_AND_ACCEPT_ES_PATCHING", "yes", "--es-path", esLocalPath.toString))(_)
      }
      result should equal(Result.Failure)
      output should include(
        """Checking if Elasticsearch is patched ...
          |ERROR: Elasticsearch was patched using ROR 0.0.1 patcher. It should be unpatched using ROR 0.0.1 and patched again with current ROR patcher. ReadonlyREST cannot be started. For patching instructions see our docs: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch
          |""".stripMargin
      )
    }
    "Unpatching is not started when metadata file is missing" in {
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

      patchMetadataFile.exists() should be(true)
      patchMetadataFile.delete()
      patchMetadataFile.exists() should be(false)

      val (unpatchResult, unpatchOutput) = captureResultAndOutput {
        RorToolsTestApp.run(Array("unpatch", "--es-path", esLocalPath.toString))(_)
      }
      unpatchResult should equal(Result.Failure)
      unpatchOutput should include(
        """Checking if Elasticsearch is patched ...
          |ERROR: Elasticsearch is likely patched by an older version of ROR, but there is no valid patch metadata present."""
          .stripMargin
      )
    }
    "Unpatching not started because ES is already patched by different version" in {
      givenPatchMetadata(
        rorVersionUsedForPatching = "0.0.1",
        esVersionThatWasPatched = esVersionUsed,
        patchedFilesMetadata = List.empty,
      )

      val (result, output) = captureResultAndOutput {
        RorToolsTestApp.run(Array("unpatch", "--es-path", esLocalPath.toString))(_)
      }
      result should equal(Result.Failure)
      output should include(
        """Checking if Elasticsearch is patched ...
          |ERROR: Elasticsearch was patched using ROR 0.0.1 patcher. It should be unpatched using ROR 0.0.1 and patched again with current ROR patcher. ReadonlyREST cannot be started. For patching instructions see our docs: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch
          |""".stripMargin
      )
    }
    "Verify correctly recognizes that patch is not applied" in {
      patchMetadataFile.exists() should be(false)
      val (verifyResult, verifyOutput) = captureResultAndOutput {
        RorToolsTestApp.run(Array("verify", "--es-path", esLocalPath.toString))(_)
      }
      verifyResult should equal(Result.Failure)
      verifyOutput should include(
        """Checking if Elasticsearch is patched ...
          |ERROR: Elasticsearch is NOT patched. ReadonlyREST cannot be used yet. For patching instructions see our docs: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch"""
          .stripMargin
      )
    }
    "Verify detects patch when metadata file is present" in {
      // Patch
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

      patchMetadataFile.exists() should be(true)

      val (verifyResult, verifyOutput) = captureResultAndOutput {
        RorToolsTestApp.run(Array("verify", "--es-path", esLocalPath.toString))(_)
      }
      verifyResult should equal(Result.Success)
      verifyOutput should include(
        """Checking if Elasticsearch is patched ...
          |Elasticsearch is patched! ReadonlyREST can be used"""
          .stripMargin
      )
    }

    "The patch is not detected when metadata file is missing and `verify` command is executed" in {
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

      patchMetadataFile.exists() should be(true)
      patchMetadataFile.delete()
      patchMetadataFile.exists() should be(false)

      val (verifyResultWithoutFile, verifyOutputWithoutFile) = captureResultAndOutput {
        RorToolsTestApp.run(Array("verify", "--es-path", esLocalPath.toString))(_)
      }
      verifyResultWithoutFile should equal(Result.Failure)
      verifyOutputWithoutFile should include(
        s"""Checking if Elasticsearch is patched ...
           |ERROR: Elasticsearch is likely patched by an older version of ROR, but there is no valid patch metadata present. In case of problems please try to unpatch using the ROR version that had been used for patching or reinstall ES.
           | - backup catalog is present, but there is no metadata file
           |""".stripMargin
      )
    }

    "The patch is not detected when metadata file is missing and `verify` command is executed (ES 9.x with detailed assertions)" excludeES(allEs6x, allEs7x, allEs8x) in {
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

      patchMetadataFile.exists() should be(true)
      val metadata = EsPatchMetadataCodec.decode(patchMetadataFile.contentAsString).toOption.get

      patchMetadataFile.delete()
      patchMetadataFile.exists() should be(false)

      val (verifyResultWithoutFile, verifyOutputWithoutFile) = captureResultAndOutput {
        RorToolsTestApp.run(Array("verify", "--es-path", esLocalPath.toString))(_)
      }
      verifyResultWithoutFile should equal(Result.Failure)
      verifyOutputWithoutFile should include(
        s"""Checking if Elasticsearch is patched ...
           |ERROR: Elasticsearch is likely patched by an older version of ROR, but there is no valid patch metadata present. In case of problems please try to unpatch using the ROR version that had been used for patching or reinstall ES.
           | - backup catalog is present, but there is no metadata file
           | - file x-pack-core-9.0.0.jar was patched by ROR ${metadata.rorVersion}
           | - file x-pack-ilm-9.0.0.jar was patched by ROR ${metadata.rorVersion}
           | - file x-pack-security-9.0.0.jar was patched by ROR ${metadata.rorVersion}
           |""".stripMargin
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
      patchMetadataFile.exists() should be(true)

      // Unpatch
      val hashBeforeUnpatching = FileUtils.calculateHash(esLocalPath)
      val (unpatchResult, unpatchOutput) = captureResultAndOutput {
        RorToolsTestApp.run(Array("unpatch", "--es-path", esLocalPath.toString))(_)
      }
      unpatchResult should equal(Result.Success)
      unpatchOutput should include(
        """Checking if Elasticsearch is patched ...
          |Elasticsearch is currently patched, restoring ...
          |Elasticsearch is unpatched! ReadonlyREST can be removed now"""
          .stripMargin
      )
      val hashAfterUnpatching = FileUtils.calculateHash(esLocalPath)

      hashBeforePatching should equal(hashAfterUnpatching)
      hashAfterPatching should equal(hashBeforeUnpatching)
      hashBeforePatching shouldNot equal(hashAfterPatching)
    }
    "Successfully patch, verify and unable to unpatch when one of the patched files was modified" in {
      // Patch
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
      patchMetadataFile.exists() should be(true)

      // Modify expected hash - simulate one of the files being modified
      modifyMetadataFile {metadata =>
        val lastHash = metadata.patchedFilesMetadata.last
        val modifiedList = metadata.patchedFilesMetadata.init :+ lastHash.copy(hash = lastHash.hash + "abc")
        metadata.copy(patchedFilesMetadata = modifiedList)
      }

      // Attempt to unpatch
      val (unpatchResult, unpatchOutput) = captureResultAndOutput {
        RorToolsTestApp.run(Array("unpatch", "--es-path", esLocalPath.toString))(_)
      }
      unpatchResult should equal(Result.Failure)
      // The assertion cannot check specific filenames, because they are ES-version specific
      unpatchOutput should include(
        """Checking if Elasticsearch is patched ...
          |ERROR: Elasticsearch was patched, but files"""
          .stripMargin
      )
      unpatchOutput should include("were modified after patching")
    }
    "Successfully patch, verify and be unable to unpatch when it is simulated in test, that patch was performed on other ES version" in {
      // Patch
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
      patchMetadataFile.exists() should be(true)

      // Modify ES version in metadata - simulate, that the patch was performed on some other ES version
      modifyMetadataFile(
        _.copy(esVersion = SemVer.unsafeParse("1.2.3"))
      )

      // Attempt to unpatch
      val (unpatchResult, unpatchOutput) = captureResultAndOutput {
        RorToolsTestApp.run(Array("unpatch", "--es-path", esLocalPath.toString))(_)
      }
      unpatchResult should equal(Result.Failure)
      // The assertion cannot check specific filenames, because they are ES-version specific
      unpatchOutput should include(
        s"""Checking if Elasticsearch is patched ...
           |ERROR: The patch was performed on Elasticsearch version 1.2.3, but currently installed ES version is 9.0.0.
           |As a result, the Elasticsearch is in a corrupted state. Please consider reinstalling it.
           |To avoid this issue in the future, please follow those steps when upgrading ES:
           | 1. Unpatch the older ES version using ror-tools
           | 2. Upgrade to the newer ES version
           | 3. Patch ES after the upgrade using ror-tools
           |For patching instructions see our docs: https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch""".stripMargin
      )
    }
  }

  private def givenPatchMetadata(rorVersionUsedForPatching: String,
                                 esVersionThatWasPatched: String,
                                 patchedFilesMetadata: List[FilePatchMetadata]): Unit = {
    File(backupDirectory.path).createDirectory()
    patchMetadataFile.createFile()
    patchMetadataFile.write(
      EsPatchMetadataCodec.encode(
        EsPatchMetadata(
          rorVersion = rorVersionUsedForPatching,
          esVersion = SemVer.unsafeParse(esVersionThatWasPatched),
          patchedFilesMetadata = patchedFilesMetadata
        )
      )
    )
  }

  private def modifyMetadataFile(f: EsPatchMetadata => EsPatchMetadata): Unit = {
    val metadata = EsPatchMetadataCodec.decode(patchMetadataFile.contentAsString).toOption.get
    patchMetadataFile.overwrite(EsPatchMetadataCodec.encode(f(metadata)))
  }


  private def captureResultAndOutput(block: InOut => Result): (Result, String) = {
    val inOut = new CapturingOutputAndMockingInput()
    val result = block(inOut)
    (result, inOut.getOutputBuffer)
  }

  private def captureResultAndOutputWithInteraction(block: InOut => Result, response: Option[String]): (Result, String) = {
    val inOut = new CapturingOutputAndMockingInput(response)
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
