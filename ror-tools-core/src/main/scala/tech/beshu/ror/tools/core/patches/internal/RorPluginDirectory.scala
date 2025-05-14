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
import tech.beshu.ror.tools.core.utils.EsDirectory
import tech.beshu.ror.tools.core.utils.EsUtil.{findTransportNetty4JarIn, readonlyrestPluginPath}

private[patches] class RorPluginDirectory(val esDirectory: EsDirectory) {

  private val rorPath: Path = readonlyrestPluginPath(esDirectory.path)
  private val backupFolderPath: Path = rorPath / "patch_backup"
  private val patchMetadataFilePath: Path = backupFolderPath / "patch_metadata"
  private val pluginPropertiesFilePath = rorPath / "plugin-descriptor.properties"

  val securityPolicyPath: Path = rorPath / "plugin-security.policy"

  def doesBackupFolderExist: Boolean = {
    os.exists(backupFolderPath)
  }

  def createBackupFolder(): Unit = {
    os.makeDir.all(path = backupFolderPath)
  }

  def clearBackupFolder(): Unit = {
    os.remove.all(target = backupFolderPath)
  }

  def backup(file: Path): Unit = {
    os.copy(from = file, to = backupFolderPath / file.last, replaceExisting = true)
  }

  def restore(file: Path): Unit = {
    os.copy(from = backupFolderPath / file.last, to = file, replaceExisting = true)
  }

  def copyToPluginPath(file: Path): Unit = {
    os.copy(from = file, to = rorPath / file.last)
  }

  def findTransportNetty4Jar: Option[Path] = {
    findTransportNetty4JarIn(rorPath)
  }

  def readEsPatchMetadata(): Option[EsPatchMetadata] = {
    if (os.exists(patchMetadataFilePath)) {
      val metadataContent = os.read(patchMetadataFilePath)
      EsPatchMetadataCodec.decode(metadataContent) match {
        case Right(metadata) => Some(metadata)
        case Left(error) => throw new IllegalStateException(s"Cannot decode ROR patch metadata file: $error. File content: [$metadataContent]")
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
  }

  def readCurrentRorVersion(): String = {
    val versionPattern = """^version=(.+)$""".r
    os.read
      .lines(pluginPropertiesFilePath).toList
      .flatMap {
        case versionPattern(version) => Some(version)
        case _ => None
      }
      .headOption
      .getOrElse(throw new IllegalStateException(s"Cannot read ROR version from ${pluginPropertiesFilePath}"))
  }

}

object RorPluginDirectory {
  final case class EsPatchMetadata(rorVersion: String,
                                   esVersion: SemVer,
                                   patchedFilesMetadata: List[FilePatchMetadata])
}