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
package tech.beshu.ror.tools.core.patches

import just.semver.SemVer
import tech.beshu.ror.tools.core.utils.EsDirectory
import tech.beshu.ror.tools.core.utils.EsUtil.readonlyrestPluginPath
import tech.beshu.ror.tools.core.utils.asm.{AlwaysGrantApplicationPermission, DeactivateAuthenticationServiceInHttpTransport, DeactivateSecurityActionFilter, DeactivateSecurityServerTransportInterceptor, MockAuthorizationInfoInAuthorizationService}

import scala.language.postfixOps
import scala.util.Try

private[patches] class Es717xPatch(esDirectory: EsDirectory,
                                   esVersion: SemVer)
  extends EsPatch {

  private val modulesPath = esDirectory.path / "modules"

  private val readonlyRestPluginPath = readonlyrestPluginPath(esDirectory.path)
  private val rorBackupFolderPath = readonlyRestPluginPath / "patch_backup"

  private val xpackCoreJar = s"x-pack-core-${esVersion.render}.jar"
  private val xpackCoreJarPath = modulesPath / "x-pack-core" / xpackCoreJar
  private val xpackCoreRorBackupPath = rorBackupFolderPath / xpackCoreJar

  private val xpackSecurityJar = s"x-pack-security-${esVersion.render}.jar"
  private val xpackSecurityJarPath = modulesPath / "x-pack-security" / xpackSecurityJar
  private val xpackSecurityRorBackupPath = rorBackupFolderPath / xpackSecurityJar

  override def isPatched: Boolean = {
    doesBackupFolderExist
  }

  override def backup(): Unit = {
    copyJarsToBackupFolder()
      .recoverWith { case ex =>
        os.remove.all(target = rorBackupFolderPath)
        throw ex
      }
  }

  override def restore(): Unit = {
    os.copy(from = xpackCoreRorBackupPath, to = xpackCoreJarPath, replaceExisting = true)
    os.copy(from = xpackSecurityRorBackupPath, to = xpackSecurityJarPath, replaceExisting = true)
    os.remove.all(target = rorBackupFolderPath)
  }

  override def execute(): Unit = {
    DeactivateSecurityActionFilter(xpackSecurityJarPath toIO)
    DeactivateSecurityServerTransportInterceptor(xpackSecurityJarPath toIO)
    DeactivateAuthenticationServiceInHttpTransport(xpackSecurityJarPath toIO)
    MockAuthorizationInfoInAuthorizationService(xpackSecurityJarPath toIO)
    AlwaysGrantApplicationPermission(xpackCoreJarPath toIO)
  }

  private def copyJarsToBackupFolder() = Try {
    os.makeDir.all(path = rorBackupFolderPath)
    os.copy(from = xpackCoreJarPath, to = xpackCoreRorBackupPath)
    os.copy(from = xpackSecurityJarPath, to = xpackSecurityRorBackupPath)
  }

  private def doesBackupFolderExist = {
    os.exists(rorBackupFolderPath)
  }

}
