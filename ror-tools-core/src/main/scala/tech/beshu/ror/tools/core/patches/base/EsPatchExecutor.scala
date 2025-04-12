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

import tech.beshu.ror.tools.core.patches.base.EsPatchExecutor.EsPatchStatus
import tech.beshu.ror.tools.core.patches.internal.RorPluginDirectory
import tech.beshu.ror.tools.core.utils.RorToolsError.*
import tech.beshu.ror.tools.core.utils.{EsDirectory, InOut, RorToolsError}

import scala.util.{Failure, Success, Try}

final class EsPatchExecutor(rorPluginDirectory: RorPluginDirectory,
                            esPatch: EsPatch)
                           (implicit inOut: InOut) {

  def patch(): Either[RorToolsError, Unit] = {
    checkWithPatchedByFileAndEsPatch() match {
      case EsPatchStatus.PatchedWithCurrentRorVersion(rorVersion) =>
        Left(EsAlreadyPatchedError(rorVersion))
      case EsPatchStatus.PatchedWithOtherRorVersion(expectedRorVersion, patchedByRorVersion) =>
        Left(EsPatchedWithDifferentVersionError(expectedRorVersion, patchedByRorVersion))
      case EsPatchStatus.NotPatched =>
        backup()
        inOut.println("Patching ...")
        esPatch.performPatching()
        inOut.println("Elasticsearch is patched! ReadonlyREST is ready to use")
        Right(())
    }

  }

  def restore(): Either[RorToolsError, Unit] = {
    checkWithPatchedByFileAndEsPatch() match {
      case EsPatchStatus.PatchedWithCurrentRorVersion(_) =>
        doRestore()
      case EsPatchStatus.PatchedWithOtherRorVersion(expectedRorVersion, patchedByRorVersion) =>
        Left(EsPatchedWithDifferentVersionError(expectedRorVersion, patchedByRorVersion))
      case EsPatchStatus.NotPatched if esPatch.isPatchApplied =>
        // The patched_by file may be missing for some reason (so the status is EsPatchStatus.NotPatched),
        // but the EsPlugin implementation recognizes as patched, so we perform restoring
        doRestore()
      case EsPatchStatus.NotPatched =>
        Left(EsNotPatchedError)
    }
  }

  def verify(): Unit = {
    checkWithPatchedByFileAndEsPatch() match {
      case EsPatchStatus.PatchedWithCurrentRorVersion(_) =>
        inOut.println("Elasticsearch is patched! ReadonlyREST can be used")
      case EsPatchStatus.PatchedWithOtherRorVersion(expectedRorVersion, patchedByRorVersion) =>
        inOut.println(EsPatchedWithDifferentVersionError(expectedRorVersion, patchedByRorVersion).message)
      case EsPatchStatus.NotPatched =>
        inOut.println(EsNotPatchedError.message)
    }
  }

  def isPatched: EsPatchStatus = checkWithPatchedByFileAndEsPatch()

  private def doRestore() = {
    inOut.println("Elasticsearch is currently patched, restoring ...")
    Try(esPatch.performRestore()) match {
      case Success(()) =>
        Right(inOut.println("Elasticsearch is unpatched! ReadonlyREST can be removed now"))
      case Failure(exception) =>
        throw exception
    }
  }

  private def backup(): Unit = {
    inOut.println("Creating backup ...")
    Try(esPatch.performBackup()) match {
      case Success(_) =>
        rorPluginDirectory.updatePatchedByRorVersion()
      case Failure(ex) =>
        rorPluginDirectory.clearBackupFolder()
        throw ex
    }
  }

  private def checkWithPatchedByFileAndEsPatch(): EsPatchStatus = {
    inOut.println("Checking if Elasticsearch is patched ...")
    val currentRorVersion = rorPluginDirectory.readCurrentRorVersion()
    val resultOfCheckingPatchedByFile =
      rorPluginDirectory.readPatchedByRorVersion() match {
        case None =>
          EsPatchStatus.NotPatched
        case Some(patchedByRorVersion) if patchedByRorVersion == currentRorVersion =>
          EsPatchStatus.PatchedWithCurrentRorVersion(currentRorVersion)
        case Some(patchedByRorVersion) =>
          EsPatchStatus.PatchedWithOtherRorVersion(currentRorVersion, patchedByRorVersion)
      }
    resultOfCheckingPatchedByFile match {
      case EsPatchStatus.PatchedWithCurrentRorVersion(currentRorVersion) =>
        if (esPatch.isPatchApplied) {
          EsPatchStatus.PatchedWithCurrentRorVersion(currentRorVersion)
        } else {
          EsPatchStatus.NotPatched
        }
      case otherPatchStatus =>
        otherPatchStatus
    }
  }
}

object EsPatchExecutor {

  sealed trait EsPatchStatus

  object EsPatchStatus {
    final case class PatchedWithCurrentRorVersion(currentRorVersion: String) extends EsPatchStatus

    final case class PatchedWithOtherRorVersion(currentRorVersion: String, patchedByRorVersion: String) extends EsPatchStatus

    case object NotPatched extends EsPatchStatus
  }

  def create(esDirectory: EsDirectory)
            (implicit inOut: InOut): EsPatchExecutor = {
    val esPatch = EsPatch.create(esDirectory)
    new EsPatchExecutor(new RorPluginDirectory(esDirectory), esPatch)
  }
}
