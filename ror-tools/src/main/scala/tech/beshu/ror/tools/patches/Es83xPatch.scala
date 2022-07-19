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
package tech.beshu.ror.tools.patches

import just.semver.SemVer
import tech.beshu.ror.tools.utils.EsUtil.{findTransportNetty4JarIn, readonlyrestPluginPath}
import tech.beshu.ror.tools.utils.ModuleOpener.openModule

import scala.language.postfixOps
import scala.util.Try

class Es83xPatch(esPath: os.Path,
                 esVersion: SemVer)
  extends EsPatch {

  private val libPath = esPath / "lib"
  private val modulesPath = esPath / "modules"

  private val readonlyRestPluginPath = readonlyrestPluginPath(esPath)
  private val rorBackupFolderPath = readonlyRestPluginPath / "patch_backup"
  private val elasticsearchJar = s"elasticsearch-${esVersion.render}.jar"
  private val elasticsearchJarOriginPath = libPath / elasticsearchJar
  private val elasticsearchJarRorBackupPath = rorBackupFolderPath / elasticsearchJar
  private val xpackCoreJar = s"x-pack-core-${esVersion.render}.jar"
  private val xpackCoreJarPath = modulesPath / "x-pack-core" / xpackCoreJar
  private val xpackCoreRorBackupPath = rorBackupFolderPath / xpackCoreJar
  private val transportNetty4ModulePath = esPath / "modules" / "transport-netty4"

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
    os.copy(from = xpackCoreRorBackupPath, to = xpackCoreJarPath, replaceExisting = true)
    os.copy(from = elasticsearchJarRorBackupPath, to = elasticsearchJarOriginPath, replaceExisting = true)
    os.remove.all(target = rorBackupFolderPath)
  }

  override def execute(): Unit = {
    findTransportNetty4JarIn(transportNetty4ModulePath) match {
      case Some(jar) =>
        os.copy(from = jar, to = readonlyRestPluginPath / jar.last)
        openModule(elasticsearchJarOriginPath toIO)
        openModule(xpackCoreJarPath toIO)
      case None =>
        new IllegalStateException(s"ReadonlyREST plugin cannot be patched due to not found transport netty4 jar")
    }
  }

  private def copyJarsToBackupFolder() = Try {
    os.makeDir.all(path = rorBackupFolderPath)
    os.copy(from = elasticsearchJarOriginPath, to = elasticsearchJarRorBackupPath)
    os.copy(from = xpackCoreJarPath, to = xpackCoreRorBackupPath)
  }

  private def doesBackupFolderExist = {
    os.exists(rorBackupFolderPath)
  }

  private def isTransportNetty4PresentInRorPluginPath = {
    findTransportNetty4JarIn(readonlyRestPluginPath).isDefined
  }

}

