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

import tech.beshu.ror.tools.core.patches.base.EsPatch.IsPatched
import tech.beshu.ror.tools.core.patches.internal.RorPluginDirectory
import tech.beshu.ror.tools.core.utils.RorToolsException.{EsAlreadyPatchedException, EsNotPatchedException, EsPatchedWithDifferentVersionException}
import tech.beshu.ror.tools.core.utils.{EsDirectory, InOut}

import scala.util.{Failure, Success, Try}

final class EsPatchExecutor(rorPluginDirectory: RorPluginDirectory,
                            esPatch: EsPatch)
                           (implicit inOut: InOut) {

  def patch(): Unit = {
    checkWithPatchedByFile() match {
      case IsPatched.WithCurrentVersion(rorVersion) =>
        throw EsAlreadyPatchedException(rorVersion)
      case IsPatched.WithDifferentVersion(expectedRorVersion, patchedByRorVersion) =>
        throw EsPatchedWithDifferentVersionException(expectedRorVersion, patchedByRorVersion)
      case IsPatched.No =>
        backup()
        inOut.println("Patching ...")
        Try(esPatch.performPatching()) match {
          case Success(()) =>
            inOut.println("Elasticsearch is patched! ReadonlyREST is ready to use")
          case Failure(exception) =>
            throw exception
        }
    }

  }

  def backup(): Unit = {
    inOut.println("Creating backup ...")
    Try(esPatch.performBackup()) match {
      case Success(_) =>
        rorPluginDirectory.updatePatchedByRorVersion()
      case Failure(ex) =>
        rorPluginDirectory.clearBackupFolder()
        throw ex
    }
  }

  def restore(): Unit = {
    checkWithPatchedByFile(ignoreMissingPatchedByFile = true) match {
      case IsPatched.WithCurrentVersion(_) =>
        inOut.println("Elasticsearch is currently patched, restoring ...")
        Try(esPatch.performRestore()) match {
          case Success(()) =>
            inOut.println("Elasticsearch is unpatched! ReadonlyREST can be removed now")
          case Failure(exception) =>
            throw exception
        }
      case IsPatched.WithDifferentVersion(expectedRorVersion, patchedByRorVersion) =>
        throw EsPatchedWithDifferentVersionException(expectedRorVersion, patchedByRorVersion)
      case IsPatched.No =>
        throw EsNotPatchedException
    }
  }

  def verify(): Unit = {
    checkWithPatchedByFile() match {
      case IsPatched.WithCurrentVersion(_) =>
        inOut.println("Elasticsearch is patched! ReadonlyREST can be used")
      case IsPatched.WithDifferentVersion(expectedRorVersion, patchedByRorVersion) =>
        inOut.println(EsPatchedWithDifferentVersionException(expectedRorVersion, patchedByRorVersion).getMessage)
      case IsPatched.No =>
        inOut.println(EsNotPatchedException.getMessage)
    }
  }

  def isPatched: IsPatched = {
    checkWithPatchedByFile() match
      case IsPatched.WithCurrentVersion(rorVersion) =>
        esPatch.patchIsApplied(rorVersion)
      case IsPatched.WithDifferentVersion(expectedRorVersion, patchedByRorVersion) =>
        throw EsPatchedWithDifferentVersionException(expectedRorVersion, patchedByRorVersion)
      case IsPatched.No =>
        throw EsNotPatchedException
  }

  private def checkWithPatchedByFile(ignoreMissingPatchedByFile: Boolean = false): IsPatched = {
    inOut.println("Checking if Elasticsearch is patched ...")
    val currentRorVersion = rorPluginDirectory.readCurrentRorVersion()
    val resultOfCheckingPatchedByFile =
      rorPluginDirectory.readPatchedByRorVersion() match {
        case None =>
          if (ignoreMissingPatchedByFile) IsPatched.WithCurrentVersion(currentRorVersion)
          else IsPatched.No
        case Some(patchedByRorVersion) if patchedByRorVersion == currentRorVersion =>
          IsPatched.WithCurrentVersion(currentRorVersion)
        case Some(patchedByRorVersion) =>
          IsPatched.WithDifferentVersion(currentRorVersion, patchedByRorVersion)
      }
    resultOfCheckingPatchedByFile match {
      case IsPatched.WithCurrentVersion(currentRorVersion) =>
        esPatch.patchIsApplied(currentRorVersion)
      case withDifferentVersion: IsPatched.WithDifferentVersion =>
        withDifferentVersion
      case IsPatched.No =>
        IsPatched.No
    }
  }
}

object EsPatchExecutor {
  def create(esDirectory: EsDirectory)
            (implicit inOut: InOut): EsPatchExecutor = {
    val esPatch = EsPatch.create(esDirectory)
    new EsPatchExecutor(new RorPluginDirectory(esDirectory), esPatch)
  }
}
