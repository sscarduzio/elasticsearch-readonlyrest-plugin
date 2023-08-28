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
import tech.beshu.ror.tools.core.utils.EsUtil.{findTransportNetty4JarIn, readonlyrestPluginPath}
import tech.beshu.ror.tools.core.utils.asm.{DeactivateSecurityActionFilter, ModifyPolicyUtilClass}
import tech.beshu.ror.tools.core.utils.{AddCreateClassLoaderPermission, EsDirectory}

import scala.language.postfixOps
import scala.util.Try

private[patches] class Es80xPatch(esDirectory: EsDirectory,
                                  esVersion: SemVer)
  extends EsPatch {

  private val modulesPath = esDirectory.path / "modules"
  private val libPath = esDirectory.path / "lib"

  private val readonlyRestPluginPath = readonlyrestPluginPath(esDirectory.path)
  private val rorBackupFolderPath = readonlyRestPluginPath / "patch_backup"

  private val xpackSecurityJar = s"x-pack-security-${esVersion.render}.jar"
  private val xpackSecurityJarPath = modulesPath / "x-pack-security" / xpackSecurityJar
  private val xpackSecurityRorBackupPath = rorBackupFolderPath / xpackSecurityJar

  private val esMainJar = s"elasticsearch-${esVersion.render}.jar"
  private val esMainJarPath = libPath / esMainJar
  private val esMainJarBackupPath = rorBackupFolderPath / esMainJar

  private val rorSecurityPolicy = s"plugin-security.policy"
  private val rorSecurityPolicyPath = readonlyRestPluginPath / rorSecurityPolicy
  private val rorSecurityPolicyBackupPath = rorBackupFolderPath / rorSecurityPolicy

  private val transportNetty4ModulePath = esDirectory.path / "modules" / "transport-netty4"

  override def isPatched: Boolean = {
    doesBackupFolderExist && isTransportNetty4PresentInRorPluginPath
  }

  override def backup(): Unit = {
    copyJarsToBackupFolder()
      .recoverWith { case ex =>
        os.remove.all(target = rorBackupFolderPath)
        throw ex
      }
  }

  override def restore(): Unit = {
    findTransportNetty4JarIn(readonlyRestPluginPath).foreach {
      os.remove
    }
    os.copy(from = rorSecurityPolicyBackupPath, to = rorSecurityPolicyPath, replaceExisting = true)
    os.copy(from = esMainJarBackupPath, to = esMainJarPath, replaceExisting = true)
    os.copy(from = xpackSecurityRorBackupPath, to = xpackSecurityJarPath, replaceExisting = true)
    os.remove.all(target = rorBackupFolderPath)
  }

  override def execute(): Unit = {
    findTransportNetty4JarIn(transportNetty4ModulePath) match {
      case Some(jar) =>
        os.copy(from = jar, to = readonlyRestPluginPath / jar.last)
        AddCreateClassLoaderPermission(rorSecurityPolicyPath toIO)
        ModifyPolicyUtilClass(esMainJarPath toIO)
        DeactivateSecurityActionFilter(xpackSecurityJarPath toIO)
      case None =>
        new IllegalStateException(s"ReadonlyREST plugin cannot be patched due to not found transport netty4 jar")
    }
  }

  private def copyJarsToBackupFolder() = Try {
    os.makeDir.all(path = rorBackupFolderPath)
    os.copy(from = rorSecurityPolicyPath, to = rorSecurityPolicyBackupPath)
    os.copy(from = esMainJarPath, to = esMainJarBackupPath)
    os.copy(from = xpackSecurityJarPath, to = xpackSecurityRorBackupPath)
  }

  private def doesBackupFolderExist = {
    os.exists(rorBackupFolderPath)
  }

  private def isTransportNetty4PresentInRorPluginPath = {
    findTransportNetty4JarIn(readonlyRestPluginPath).isDefined
  }

}
