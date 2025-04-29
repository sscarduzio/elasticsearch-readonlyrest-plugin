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

import java.util.UUID
import java.util.jar.{JarEntry, JarFile, JarOutputStream}
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.{Failure, Success, Try}

object JarManifestModifier {

  private val patchedByRorVersionPropertyName = "Patched-By-Ror-Version"

  def addPatchedByRorVersionProperty(file: File, rorVersion: String): Unit = {
    val tempJarFile = File(s"temp-${UUID.randomUUID()}.jar")
    val jarFile = new JarFile(file.toJava)
    val jarOutput = new JarOutputStream(tempJarFile.newOutputStream.buffered)
    try {
      jarFile
        .entries()
        .asIterator()
        .asScala
        .foreach { entry =>
          val name = entry.getName
          if (name.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
            // Modify manifest file by adding new property and copy the result to the temp jar
            val manifest = jarFile.getManifest
            manifest.getMainAttributes.putValue(patchedByRorVersionPropertyName, rorVersion)
            val newEntry = new JarEntry(name)
            jarOutput.putNextEntry(newEntry)
            manifest.write(jarOutput)
            jarOutput.closeEntry()
          } else {
            // Copy file other than manifest without changes to the temp jar
            val newEntry = new JarEntry(name)
            jarOutput.putNextEntry(newEntry)
            val in = jarFile.getInputStream(entry)
            in.transferTo(jarOutput)
            in.close()
            jarOutput.closeEntry()
          }
        }
      val copyOptions: File.CopyOptions = File.CopyOptions(overwrite = true)
      // Replace original jar with the newly created modified jar
      tempJarFile.moveTo(file)(copyOptions)
    } finally {
      jarOutput.close()
    }
  }

  def findPatchedFiles(esDirectory: EsDirectory): List[PatchedJarFile] = {
    val directory = File(esDirectory.modulesPath.wrapped)
    directory.walk().filter(_.name.endsWith(".jar")).toList.flatMap { file =>
      Try(new JarFile(file.toJava)) match {
        case Success(jarFile) =>
          try {
            val rorVersion = Option(jarFile.getManifest.getMainAttributes.getValue(patchedByRorVersionPropertyName))
            rorVersion.map(PatchedJarFile(file.name, _))
          } finally {
            jarFile.close()
          }
        case Failure(exception) =>
          Console.err.println(s"Could not read jar file ${file.name}, $exception")
          None
      }
    }
  }

  final case class PatchedJarFile(name: String, patchedByRorVersion: String)

}
