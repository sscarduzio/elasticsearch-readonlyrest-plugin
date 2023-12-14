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
import tech.beshu.ror.tools.core.utils.EsDirectory
import tech.beshu.ror.tools.core.utils.EsUtil.{findTransportNetty4JarIn, readonlyrestPluginPath}

private [patches] class RorPluginDirectory(val esDirectory: EsDirectory) {

  private val path: Path = readonlyrestPluginPath(esDirectory.path)
  private val backupFolderPath: Path = path / "patch_backup"

  val securityPolicyPath: Path = path / s"plugin-security.policy"

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
    os.copy(from = file, to = backupFolderPath / file.last)
  }

  def restore(file: Path): Unit = {
    os.copy(from =  backupFolderPath / file.last, to = file)
  }

  def copyToPluginPath(file: Path): Unit = {
    os.copy(from = file, to = path / file.last)
  }

  def isTransportNetty4PresentInRorPluginPath: Boolean = {
    findTransportNetty4JarIn(path).isDefined
  }

  def findTransportNetty4Jar: Option[Path] = {
    findTransportNetty4JarIn(path)
  }
}
