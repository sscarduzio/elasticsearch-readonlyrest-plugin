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
import tech.beshu.ror.tools.utils.EsUtil.readonlyrestPluginPath
import tech.beshu.ror.tools.utils.ModuleOpener.openModule

import scala.language.postfixOps
import scala.util.{Failure, Try}

class Es83xPatch(esPath: os.Path,
                 esVersion: SemVer)
  extends EsPatch {

  private val libPath = esPath / "lib"
  private val modulesPath = esPath / "modules"

  private val rorBackupFolderPath = readonlyrestPluginPath(esPath) / "patch_backup"
  private val elasticsearchJar = s"elasticsearch-${esVersion.render}.jar"
  private val elasticsearchJarOriginPath = libPath / elasticsearchJar
  private val elasticsearchJarRorBackupPath = rorBackupFolderPath / elasticsearchJar
  private val xpackCoreJar = s"x-pack-core-${esVersion.render}.jar"
  private val xpackCoreJarPath = modulesPath / "x-pack-core" / xpackCoreJar
  private val xpackCoreRorBackupPath = rorBackupFolderPath / xpackCoreJar

  private val es80xPatch = new Es80xPatch(esPath)

  override def assertIsPatched(): Try[Unit] = Try {
    if (!(os.exists(p = rorBackupFolderPath) && es80xPatch.assertIsPatched().isSuccess)) {
      throw new IllegalStateException("ReadonlyREST plugin is not patched yet")
    }
  }

  override def backup(): Try[Unit] = {
    for {
      _ <- copyJarsToBackupFolder()
      _ <- es80xPatch.backup()
    } yield ()
  } recoverWith {
    case ex =>
      os.remove(target = rorBackupFolderPath)
      Failure(ex)
  }

  override def restore(): Try[Unit] = Try {
    es80xPatch.restore()
    os.copy(from = xpackCoreRorBackupPath, to = xpackCoreJarPath, replaceExisting = true)
    os.copy(from = elasticsearchJarRorBackupPath, to = elasticsearchJarOriginPath, replaceExisting = true)
    os.remove(target = rorBackupFolderPath)
  }

  override def execute(): Try[Unit] = {
    for {
      _ <- Try(openModule(elasticsearchJarOriginPath toIO))
      _ <- Try(openModule(xpackCoreJarPath toIO))
      _ <- es80xPatch.execute()
    } yield ()
  }

  private def copyJarsToBackupFolder() = Try {
    os.makeDir.all(path = rorBackupFolderPath)
    os.copy(from = elasticsearchJarOriginPath, to = elasticsearchJarRorBackupPath)
    os.copy(from = xpackCoreJarPath, to = xpackCoreRorBackupPath)
  }
}

