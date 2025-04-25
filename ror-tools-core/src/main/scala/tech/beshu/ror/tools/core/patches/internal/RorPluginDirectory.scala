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

import os.Path
import tech.beshu.ror.tools.core.patches.internal.FilePatch.FilePatchMetadata
import tech.beshu.ror.tools.core.patches.internal.RorPluginDirectory.filePatchPrefix
import tech.beshu.ror.tools.core.utils.EsDirectory
import tech.beshu.ror.tools.core.utils.EsUtil.{findTransportNetty4JarIn, readonlyrestPluginPath}

import java.net.{URLDecoder, URLEncoder}

private[patches] class RorPluginDirectory(val esDirectory: EsDirectory) {

  private val rorPath: Path = readonlyrestPluginPath(esDirectory.path)
  private val backupFolderPath: Path = rorPath / "patch_backup"
  private val patchedByFilePath: Path = backupFolderPath / "patched_by"
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

  def isTransportNetty4PresentInRorPluginPath: Boolean = {
    findTransportNetty4JarIn(rorPath).isDefined
  }

  def findTransportNetty4Jar: Option[Path] = {
    findTransportNetty4JarIn(rorPath)
  }

  def readPatchedByRorVersion(): Option[String] = {
    Option.when(os.exists(patchedByFilePath)) {
      os.read(patchedByFilePath)
    }
  }

  def updatePatchedByRorVersion(): Unit = {
    os.remove(patchedByFilePath, checkExists = false)
    os.write(patchedByFilePath, readCurrentRorVersion())
  }


  def readPatchMetadataFile(): Option[List[FilePatchMetadata]] = {
    Option.when(os.exists(patchMetadataFilePath)) {
      (os.read(patchMetadataFilePath): String)
        .split("\n") // process lines separately
        .filter(_.startsWith(filePatchPrefix)) // only lines that contain information about file patches
        .map(_.replaceFirst(filePatchPrefix, "")) // remove the line prefix marking the file patch
        .map(_.split("=", 2)) // split only on the first '='
        .collect { case Array(path, hash) => FilePatchMetadata(Path(URLDecoder.decode(path, "UTF-8")), hash) }
        .toList
    }
  }

  def updatePatchMetadataFile(items: List[FilePatchMetadata]): Unit = {
    lazy val fileContent = items.map { item =>
      val encodedFilePath = URLEncoder.encode(item.path.toString, "UTF-8")
      s"$filePatchPrefix$encodedFilePath=${item.hash}"
    }.mkString("\n")
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
  private val filePatchPrefix = "filePatch:"
}