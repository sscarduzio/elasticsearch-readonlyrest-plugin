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

import scala.util.{Failure, Success, Try}

private[patches] abstract class SimpleEsPatch(rorPluginDirectory: RorPluginDirectory,
                                              esVersion: SemVer,
                                              filePatchCreators: FilePatchCreator[_ <: FilePatch]*)
  extends EsPatch {

  private val filePatches: MultiFilePatch = new MultiFilePatch(
    filePatchCreators.map(_.create(rorPluginDirectory, esVersion)): _*
  )

   override def isPatched: IsPatched = {
    if(rorPluginDirectory.doesBackupFolderExist) {
      checkWithPatchedByFile(rorPluginDirectory)
    } else {
      No(Cause.NotPatchedAtAll)
    }
  }

  override def backup(): Unit = {
    copyJarsToBackupFolder() match {
      case Success(_) =>
        rorPluginDirectory.updatePatchedByRorVersion()
      case Failure(ex) =>
        rorPluginDirectory.clearBackupFolder()
        throw ex
    }
  }

  override def restore(): Unit = {
    filePatches.restore()
    rorPluginDirectory.clearBackupFolder()
  }

  override def execute(): Unit = {
    filePatches.patch()
  }

  private def copyJarsToBackupFolder() = Try {
    rorPluginDirectory.createBackupFolder()
    filePatches.backup()
  }
}
