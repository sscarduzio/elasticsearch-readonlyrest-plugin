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
package tech.beshu.ror.tools.core.patches.internal

import just.semver.SemVer
import os.Path
import tech.beshu.ror.tools.core.patches.base.EsPatchMetadataCodec
import tech.beshu.ror.tools.core.patches.internal.FilePatch.FilePatchMetadata
import tech.beshu.ror.tools.core.patches.internal.RorPluginDirectory.EsPatchMetadata
import tech.beshu.ror.tools.core.utils.EsUtil.{
  findTransportNetty4JarIn,
  findTransportNetty4JarsIn,
  readonlyrestPluginPath
}
import tech.beshu.ror.tools.core.utils.FileUtils.*
import tech.beshu.ror.tools.core.utils.FileUtils.osPathToFile
import tech.beshu.ror.tools.core.utils.{EsDirectory, FileUtils}

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{AccessDeniedException, Files}
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

private[patches] class RorPluginDirectory(val esDirectory: EsDirectory) {

  private val rorPath: Path = readonlyrestPluginPath(esDirectory.path)
  val backupFolderPath: Path = rorPath / "patch_backup"
  val patchMetadataFilePath: Path = backupFolderPath / "patch_metadata"
  private val pluginPropertiesFilePath = rorPath / "plugin-descriptor.properties"

  val securityPolicyPath: Path = rorPath / "plugin-security.policy"

  def doesBackupFolderExist: Boolean = {
    os.exists(backupFolderPath)
  }

  def createBackupFolder(): Unit = {
    os.makeDir.all(path = backupFolderPath)
    // Permissions come from the plugin folder, not from the umask (umask 077 blocks the ES user)
    backupFolderPath.setFilePermissionsAndOwnerCopiedFrom(rorPath)
  }

  def clearBackupFolder(): Unit = {
    os.remove.all(target = backupFolderPath)
  }

  def backup(originalFile: Path): Unit = {
    val backedUpFile = backupFolderPath / originalFile.last
    os.copy(from = originalFile, to = backedUpFile, replaceExisting = true, copyAttributes = true)
    backedUpFile.setFilePermissionsAndOwnerCopiedFrom(originalFile)
  }

  // A file with the content of its backup stays as it is. So the restore after a patching that failed before it
  // changed a file needs no write permission for that file (e.g. a read-only module folder of a non-root user).
  def restore(file: Path): Unit = {
    val backedUpFile = backupFolderPath / file.last
    if (!(os.exists(file) && FileUtils.haveSameContent(file.toNIO, backedUpFile.toNIO))) {
      os.copy(from = backedUpFile, to = file, replaceExisting = true, copyAttributes = true)
      file.setFilePermissionsAndOwnerCopiedFrom(backedUpFile)
    }
  }

  def copyToPluginPath(file: Path): Unit = {
    val copiedFile = rorPath / file.last
    os.copy(from = file, to = copiedFile)
    copiedFile.setFilePermissionsAndOwnerCopiedFrom(file)
  }

  def findTransportNetty4Jar: Option[Path] = {
    findTransportNetty4JarIn(rorPath)
  }

  def removeTransportNetty4Jars(): Unit = {
    findTransportNetty4JarsIn(rorPath).foreach(os.remove)
  }

  def readEsPatchMetadata(): Option[EsPatchMetadata] = {
    if (os.exists(patchMetadataFilePath)) {
      val metadataContent = os.read(patchMetadataFilePath)
      EsPatchMetadataCodec.decode(metadataContent) match {
        case Right(metadata) => Some(metadata)
        case Left(error)     =>
          throw new IllegalStateException(
            s"Cannot decode ROR patch metadata file: $error. File content: [$metadataContent]"
          )
      }
    } else None
  }

  def updateEsPatchMetadata(items: List[FilePatchMetadata]): Unit = {
    lazy val fileContent = EsPatchMetadataCodec.encode(
      EsPatchMetadata(
        rorVersion = readCurrentRorVersion(),
        esVersion = esDirectory.readEsVersion(),
        patchedFilesMetadata = items,
      )
    )
    os.remove(patchMetadataFilePath, checkExists = false)
    os.write(patchMetadataFilePath, fileContent)
    patchMetadataFilePath.setFilePermissionsAndOwnerCopiedFrom(pluginPropertiesFilePath)
  }

  // True when this process has no permission to read the patch metadata file or its folder
  // (e.g. root patched ES, and ES runs as another user). Then the caller must report a permission
  // problem, not a corrupted patch: without access to the folder, nobody can tell if the file exists.
  def isEsPatchMetadataInaccessible: Boolean = {
    val path = patchMetadataFilePath.toNIO
    Try(Files.readAttributes(path, classOf[BasicFileAttributes])) match {
      case Success(_)                        => !Files.isReadable(path)
      case Failure(_: AccessDeniedException) => true
      case Failure(_)                        => false
    }
  }

  def readCurrentRorVersion(): String = {
    val versionPattern = """^version=(.+)$""".r
    os.read
      .lines(pluginPropertiesFilePath)
      .toList
      .flatMap {
        case versionPattern(version) => Some(version)
        case _                       => None
      }
      .headOption
      .getOrElse(throw new IllegalStateException(s"Cannot read ROR version from ${pluginPropertiesFilePath}"))
  }

}

object RorPluginDirectory {
  final case class EsPatchMetadata(rorVersion: String, esVersion: SemVer, patchedFilesMetadata: List[FilePatchMetadata])
}
