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
package tech.beshu.ror.tools.core.patches.base

import just.semver.SemVer
import tech.beshu.ror.tools.core.patches.base.EsPatch.IsPatched
import tech.beshu.ror.tools.core.patches.base.EsPatch.IsPatched.No
import tech.beshu.ror.tools.core.patches.base.EsPatch.IsPatched.No.Cause
import tech.beshu.ror.tools.core.patches.internal.filePatchers.FilePatchCreator
import tech.beshu.ror.tools.core.patches.internal.{FilePatch, MultiFilePatch, RorPluginDirectory}

import scala.util.Try

private [patches] abstract class TransportNetty4AwareEsPatch(rorPluginDirectory: RorPluginDirectory,
                                                             esVersion: SemVer,
                                                             filePatchCreators: FilePatchCreator[_ <: FilePatch]*)
  extends EsPatch {

  private val filePatches: MultiFilePatch = new MultiFilePatch(
    filePatchCreators.map(_.create(rorPluginDirectory, esVersion)): _*
  )

  override def isPatched: IsPatched = {
    val backupExists = rorPluginDirectory.doesBackupFolderExist
    val transportNetty4FoundInRorDir = rorPluginDirectory.isTransportNetty4PresentInRorPluginPath
    if (backupExists && transportNetty4FoundInRorDir) {
      checkWithPatchedByFile(rorPluginDirectory)
    } else if (!backupExists && !transportNetty4FoundInRorDir) {
      No(Cause.NotPatchedAtAll)
    } else {
      val possiblyCorruptedEsFiles = filePatches.files.filterNot(rorPluginDirectory.isRorPluginPath).map(_.toIO)
      throw new IllegalStateException(
        s"""
          |ES Corrupted! Something went wrong during patching/unpatching and the current state of ES installation is corrupted.
          |To recover from this state, please uninstall ReadonlyREST plugin and copy the corrupted files from ES binaries (https://www.elastic.co/downloads/elasticsearch):
          |${possiblyCorruptedEsFiles.map(_.toString).map(f => s"- $f").mkString("\n")}
          |""".stripMargin)
    }
  }

  override def backup(): Unit = {
    copyJarsToBackupFolder()
      .recoverWith { case ex =>
        rorPluginDirectory.clearBackupFolder()
        throw ex
      }
  }

  override def restore(): Unit = {
    rorPluginDirectory.findTransportNetty4Jar.foreach {
      os.remove
    }
    filePatches.restore()
    rorPluginDirectory.clearBackupFolder()
  }

  override def execute(): Unit = {
    rorPluginDirectory.esDirectory.findTransportNetty4Jar match {
      case Some(transportNetty4Jar) =>
        rorPluginDirectory.copyToPluginPath(transportNetty4Jar)
        filePatches.patch()
      case None =>
        throw new IllegalStateException(s"ReadonlyREST plugin cannot be patched due to not found transport netty4 jar")
    }
  }

  private def copyJarsToBackupFolder() = Try {
    rorPluginDirectory.createBackupFolder()
    filePatches.backup()
  }

}
