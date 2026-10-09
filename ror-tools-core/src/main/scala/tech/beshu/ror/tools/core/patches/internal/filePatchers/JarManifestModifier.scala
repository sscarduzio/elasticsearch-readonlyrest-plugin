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
package tech.beshu.ror.tools.core.patches.internal.filePatchers

import better.files.*
import tech.beshu.ror.tools.core.utils.EsDirectory
import tech.beshu.ror.tools.core.utils.FileUtils.replaceKeepingPermissionsAndOwner

import java.util.UUID
import java.util.jar.{JarEntry, JarFile, JarOutputStream}
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Using

object JarManifestModifier {

  private val patchedByRorVersionPropertyName = "Patched-By-Ror-Version"

  def addPatchedByRorVersionProperty(file: File, rorVersion: String): Unit = {
    file.replaceKeepingPermissionsAndOwner {
      val tempJarFile = File(s"temp-${UUID.randomUUID()}.jar")
      Using(new JarFile(file.toJava)) { jarFile =>
        // Using the better-files temporary files causes problems, probably because of permission issues when copying the file at the end of this method.
        val manifest = jarFile.getManifest
        manifest.getMainAttributes.putValue(patchedByRorVersionPropertyName, rorVersion)
        Using(new JarOutputStream(tempJarFile.newOutputStream.buffered, manifest)) { jarOutput =>
          copyJarContentExceptManifestFile(jarFile, jarOutput)
        }.fold(
          ex =>
            throw IllegalStateException(
              s"Could not copy content of jar file ${file.name} because of [${ex.getMessage}]",
              ex
            ),
          (_: Unit) => ()
        )
      }.fold(
        ex =>
          throw IllegalStateException(
            s"Could not add ROR version to jar file ${file.name} because of [${ex.getMessage}]",
            ex
          ),
        (_: Unit) => ()
      )
      tempJarFile.moveTo(file)(File.CopyOptions(overwrite = true))
    }
  }

  // The patches change jars in the lib and modules folders
  def findPatchedFiles(esDirectory: EsDirectory): List[PatchedJarFile] = {
    val directories = List(esDirectory.libPath, esDirectory.modulesPath).map(path => File(path.wrapped))
    directories.flatMap(_.walk().filter(_.name.endsWith(".jar"))).flatMap { file =>
      Using(new JarFile(file.toJava)) { jarFile =>
        val rorVersion = Option(jarFile.getManifest.getMainAttributes.getValue(patchedByRorVersionPropertyName))
        rorVersion.map(PatchedJarFile(file.name, _))
      }.toOption.flatten
    }
  }

  final case class PatchedJarFile(name: String, patchedByRorVersion: String)

  private def copyJarContentExceptManifestFile(originalJarFile: JarFile, jarOutput: JarOutputStream): Unit = {
    originalJarFile.entries().asIterator().asScala.foreach { entry =>
      val name = entry.getName
      if (!name.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
        val newEntry = new JarEntry(name)
        newEntry.setTime(entry.getTime)
        jarOutput.putNextEntry(newEntry)
        Using(originalJarFile.getInputStream(entry))(_.transferTo(jarOutput)).fold(
          ex =>
            throw IllegalStateException(
              s"Could not copy content of ${entry.getName} because of [${ex.getMessage}]",
              ex
            ),
          (_: Long) => ()
        )
        jarOutput.closeEntry()
      }
    }
  }

}
