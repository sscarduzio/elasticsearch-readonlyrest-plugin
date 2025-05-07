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
import tech.beshu.ror.tools.core.patches.base.EsPatchExecutor.EsPatchStatus
import tech.beshu.ror.tools.core.patches.base.EsPatchExecutor.EsPatchStatus.*
import tech.beshu.ror.tools.core.patches.base.EsPatchExecutor.PatchProblem.*
import tech.beshu.ror.tools.core.patches.internal.FilePatch.FilePatchMetadata
import tech.beshu.ror.tools.core.patches.internal.RorPluginDirectory
import tech.beshu.ror.tools.core.patches.internal.filePatchers.JarManifestModifier
import tech.beshu.ror.tools.core.patches.internal.filePatchers.JarManifestModifier.PatchedJarFile
import tech.beshu.ror.tools.core.utils.RorToolsError.*
import tech.beshu.ror.tools.core.utils.{EsDirectory, FileUtils, InOut, RorToolsError}

import scala.util.{Failure, Success, Try}

final class EsPatchExecutor(rorPluginDirectory: RorPluginDirectory,
                            esPatch: EsPatch)
                           (implicit inOut: InOut) {

  def patch(): Either[RorToolsError, Unit] = {
    checkWithPatchedByFileAndEsPatch() match {
      case NotPatched =>
        doPatch()
      case PatchedWithCurrentRorVersion(rorVersion) =>
        Left(EsAlreadyPatchedError(rorVersion))
      case PatchProblemDetected(patchProblem) =>
        Left(patchProblem.rorToolsError)
    }

  }

  def restore(): Either[RorToolsError, Unit] = {
    checkWithPatchedByFileAndEsPatch() match {
      case NotPatched =>
        Left(EsNotPatchedError)
      case PatchedWithCurrentRorVersion(_) =>
        doRestore()
      case PatchProblemDetected(patchProblem) =>
        Left(patchProblem.rorToolsError)
    }
  }

  def verify(): Either[RorToolsError, Unit] = {
    checkWithPatchedByFileAndEsPatch() match {
      case NotPatched =>
        Left(EsNotPatchedError)
      case PatchedWithCurrentRorVersion(_) =>
        inOut.println("Elasticsearch is patched! ReadonlyREST can be used")
        Right(())
      case PatchProblemDetected(patchProblem) =>
        Left(patchProblem.rorToolsError)
    }
  }

  def isPatched: EsPatchStatus = checkWithPatchedByFileAndEsPatch()

  private def doPatch(): Either[RorToolsError, Unit] = {
    backup()
    inOut.println("Patching ...")
    Try(esPatch.performPatching()) match {
      case Success(filePatchMetadataList) =>
        rorPluginDirectory.updateEsPatchMetadata(filePatchMetadataList)
        inOut.println("Elasticsearch is patched! ReadonlyREST is ready to use")
        Right(())
      case Failure(ex) =>
        rorPluginDirectory.clearBackupFolder()
        throw ex
    }
  }

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
        ()
      case Failure(ex) =>
        rorPluginDirectory.clearBackupFolder()
        throw ex
    }
  }

  private def checkWithPatchedByFileAndEsPatch(): EsPatchStatus = {
    inOut.println("Checking if Elasticsearch is patched ...")
    val currentRorVersion = rorPluginDirectory.readCurrentRorVersion()
    val currentEsVersion = rorPluginDirectory.esDirectory.readEsVersion()
    rorPluginDirectory.readEsPatchMetadata() match {
      case Some(metadata) if metadata.rorVersion == currentRorVersion && metadata.esVersion == currentEsVersion =>
        validatePatchedFiles(metadata.patchedFilesMetadata) match {
          case Right(()) =>
            PatchedWithCurrentRorVersion(currentRorVersion)
          case Left(invalidFiles) =>
            PatchProblemDetected(CorruptedPatchWithIllegalFileModificationsDetected(invalidFiles))
        }
      case Some(metadata) if metadata.rorVersion == currentRorVersion && !(metadata.esVersion == currentEsVersion) =>
        PatchProblemDetected(PatchPerformedOnOtherEsVersion(currentEsVersion.render, metadata.esVersion.render))
      case Some(metadata) =>
        PatchProblemDetected(PatchedWithOtherRorVersion(currentRorVersion, metadata.rorVersion))
      case None =>
        checkSuspectedCorruptedPatchState()
    }
  }

  private def checkSuspectedCorruptedPatchState(): EsPatchStatus = {
    // It is the situation, when the metadata file does not exist.
    // In that case we perform additional checks, in order to detect corrupted patch:
    // - we check whether the backup folder exists
    // - we search for any patched jar files
    val backupFolderExists = rorPluginDirectory.doesBackupFolderExist
    val patchedJarFiles = searchForPatchedJarFiles() match {
      case Left(files) => Some(files)
      case Right(()) => None
    }
    if (backupFolderExists || patchedJarFiles.nonEmpty) {
      PatchProblemDetected(
        CorruptedPatchWithoutValidMetadata(
          backupFolderIsPresent = backupFolderExists,
          patchedJarFiles = patchedJarFiles.getOrElse(List.empty)
        )
      )
    }
    else NotPatched
  }

  private def validatePatchedFiles(patchedFilesMetadata: List[FilePatchMetadata]): Either[List[os.Path], Unit] = {
    patchedFilesMetadata.map { filePatchMetadata =>
      val currentHash = FileUtils.calculateFileHash(filePatchMetadata.path.wrapped)
      if (filePatchMetadata.hash == currentHash) Right(()) else Left(filePatchMetadata.path)
    }.partitionMap(identity) match {
      case (paths, _) if paths.nonEmpty => Left(paths)
      case _ => Right(())
    }
  }

  private def searchForPatchedJarFiles(): Either[List[PatchedJarFile], Unit] = {
    JarManifestModifier.findPatchedFiles(rorPluginDirectory.esDirectory) match {
      case Nil => Right(())
      case nel => Left(nel)
    }
  }

}

object EsPatchExecutor {

  sealed trait EsPatchStatus

  object EsPatchStatus {
    case object NotPatched extends EsPatchStatus

    final case class PatchedWithCurrentRorVersion(currentRorVersion: String) extends EsPatchStatus

    final case class PatchProblemDetected(patchProblem: PatchProblem) extends EsPatchStatus
  }

  sealed trait PatchProblem

  object PatchProblem {
    final case class PatchPerformedOnOtherEsVersion(currentEsVersion: String, patchPerformedOnEsVersion: String) extends PatchProblem

    final case class PatchedWithOtherRorVersion(currentRorVersion: String, patchedByRorVersion: String) extends PatchProblem

    final case class CorruptedPatchWithoutValidMetadata(backupFolderIsPresent: Boolean, patchedJarFiles: List[PatchedJarFile]) extends PatchProblem

    final case class CorruptedPatchWithIllegalFileModificationsDetected(files: List[os.Path]) extends PatchProblem
  }

  implicit class PatchProblemOps(val patchProblem: PatchProblem) extends AnyVal {
    def rorToolsError: RorToolsError = patchProblem match {
      case PatchProblem.PatchPerformedOnOtherEsVersion(currentEsVersion, patchPerformedOnEsVersion) =>
        PatchPerformedOnOtherEsVersionError(currentEsVersion, patchPerformedOnEsVersion)
      case PatchProblem.PatchedWithOtherRorVersion(expectedRorVersion, patchedByRorVersion) =>
        EsPatchedWithDifferentVersionError(expectedRorVersion, patchedByRorVersion)
      case PatchProblem.CorruptedPatchWithIllegalFileModificationsDetected(files) =>
        CorruptedPatchWithIllegalFileModificationsDetectedError(files)
      case PatchProblem.CorruptedPatchWithoutValidMetadata(backupFolderPresent, patchedJarFiles) =>
        CorruptedPatchWithoutValidMetadataError(backupFolderPresent, patchedJarFiles)
    }
  }

  def create(esDirectory: EsDirectory)
            (implicit inOut: InOut): EsPatchExecutor = {
    val esPatch = EsPatch.create(esDirectory)
    new EsPatchExecutor(new RorPluginDirectory(esDirectory), esPatch)
  }
}
