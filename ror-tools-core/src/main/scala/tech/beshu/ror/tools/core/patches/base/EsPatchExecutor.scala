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
import tech.beshu.ror.tools.core.patches.base.EsPatchExecutor.EsPatchStatus.*
import tech.beshu.ror.tools.core.patches.internal.RorPluginDirectory
import tech.beshu.ror.tools.core.utils.RorToolsError.*
import tech.beshu.ror.tools.core.utils.{EsDirectory, FileUtils, InOut, RorToolsError}

import scala.util.{Failure, Success, Try}

final class EsPatchExecutor(rorPluginDirectory: RorPluginDirectory,
                            esPatch: EsPatch)
                           (implicit inOut: InOut) {

  def patch(): Either[RorToolsError, Unit] = {
    checkWithPatchedByFileAndEsPatch() match {
      case PatchedWithCurrentRorVersion(rorVersion) =>
        Left(EsAlreadyPatchedError(rorVersion))
      case PatchedWithOtherRorVersion(expectedRorVersion, patchedByRorVersion) =>
        Left(EsPatchedWithDifferentVersionError(expectedRorVersion, patchedByRorVersion))
      case IllegalFileModificationsDetectedInPatchedFiles(invalidFiles) =>
        Left(IllegalFileModificationsDetectedInPatchedFilesError(invalidFiles))
      case NotPatched =>
        doPatch()
    }

  }

  def restore(): Either[RorToolsError, Unit] = {
    checkWithPatchedByFileAndEsPatch() match {
      case PatchedWithCurrentRorVersion(_) =>
        inOut.println("Elasticsearch is currently patched, restoring ...")
        doRestore()
      case PatchedWithOtherRorVersion(expectedRorVersion, patchedByRorVersion) =>
        Left(EsPatchedWithDifferentVersionError(expectedRorVersion, patchedByRorVersion))
      case NotPatched =>
        Left(EsNotPatchedError)
      case IllegalFileModificationsDetectedInPatchedFiles(files) =>
        Left(IllegalFileModificationsDetectedInPatchedFilesError(files))
      // We have to handle this case explicitly, because older versions of patcher did not save the patched_by file
      // (when TransportNetty4AwareEsPatch was used, mostly for ES 8.x) and we need to be able to unpatch them too.
      case PatchedByOtherRorVersionWithoutValidMetadata() =>
        Left(PatchedByOtherRorVersionWithoutValidMetadataError)
    }
  }

  def verify(): Either[RorToolsError, Unit] = {
    checkWithPatchedByFileAndEsPatch() match {
      case PatchedWithCurrentRorVersion(_) =>
        inOut.println("Elasticsearch is patched! ReadonlyREST can be used")
        Right(())
      // We have to handle this case explicitly, because older versions of patcher did not save the patched_by file
      // (when TransportNetty4AwareEsPatch was used, mostly for ES 8.x) and we need to be able to recognize that case too.
      case PatchedByOtherRorVersionWithoutValidMetadata() =>
        inOut.println("Elasticsearch is most likely patched, but there is no valid patch metadata present")
        Right(())
      case PatchedWithOtherRorVersion(expectedRorVersion, patchedByRorVersion) =>
        Left(EsPatchedWithDifferentVersionError(expectedRorVersion, patchedByRorVersion))
      case NotPatched =>
        Left(EsNotPatchedError)
    }
  }

  def isPatched: EsPatchStatus = checkWithPatchedByFileAndEsPatch()

  private def doPatch(): Either[RorToolsError, Unit] = {
    backup()
    inOut.println("Patching ...")
    Try(esPatch.performPatching()) match {
      case Success(filePatchMetadataList) =>
        rorPluginDirectory.updatePatchMetadataFile(filePatchMetadataList)
        inOut.println("Elasticsearch is patched! ReadonlyREST is ready to use")
        Right(())
      case Failure(ex) =>
        rorPluginDirectory.clearBackupFolder()
        throw ex
    }
  }

  private def doRestore() = {
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
    (rorPluginDirectory.readPatchedByRorVersion(), validatePatchedFiles()) match {
      case (None, _) =>
        if (esPatch.isPatchApplied) PatchedByOtherRorVersionWithoutValidMetadata()
        else NotPatched
      case (Some(patchedByRorVersion), Some(Left(invalidFiles))) if patchedByRorVersion == currentRorVersion =>
        IllegalFileModificationsDetectedInPatchedFiles(invalidFiles)
      case (Some(patchedByRorVersion), Some(Right(()))) if patchedByRorVersion == currentRorVersion =>
        if (esPatch.isPatchApplied) PatchedWithCurrentRorVersion(currentRorVersion)
        else NotPatched
      case (Some(patchedByRorVersion), _) =>
        PatchedWithOtherRorVersion(currentRorVersion, patchedByRorVersion)
    }
  }

  private def validatePatchedFiles(): Option[Either[List[os.Path], Unit]] = {
    rorPluginDirectory
      .readPatchMetadataFile()
      .map(_.map { filePatchMetadata =>
        val currentHash = FileUtils.calculateFileHash(filePatchMetadata.path.wrapped)
        if (filePatchMetadata.hash == currentHash) Right(()) else Left(filePatchMetadata.path)
      }.partitionMap(identity) match {
        case (paths, _) if paths.nonEmpty => Left(paths)
        case (_, _ :: _) => Right(())
      })
  }

}

object EsPatchExecutor {

  sealed trait EsPatchStatus

  object EsPatchStatus {
    final case class PatchedWithCurrentRorVersion(currentRorVersion: String) extends EsPatchStatus

    final case class PatchedWithOtherRorVersion(currentRorVersion: String, patchedByRorVersion: String) extends EsPatchStatus

    final case class PatchedByOtherRorVersionWithoutValidMetadata() extends EsPatchStatus

    final case class IllegalFileModificationsDetectedInPatchedFiles(files: List[os.Path]) extends EsPatchStatus

    case object NotPatched extends EsPatchStatus
  }

  def create(esDirectory: EsDirectory)
            (implicit inOut: InOut): EsPatchExecutor = {
    val esPatch = EsPatch.create(esDirectory)
    new EsPatchExecutor(new RorPluginDirectory(esDirectory), esPatch)
  }
}
