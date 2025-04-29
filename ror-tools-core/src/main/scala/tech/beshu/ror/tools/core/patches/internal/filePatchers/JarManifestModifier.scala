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
import java.util.jar.{JarEntry, JarOutputStream}
import java.util.zip.{ZipFile, ZipInputStream}
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

object JarManifestModifier {

  val patchedByRorVersionPropertyName = "Patched-By-Ror-Version"

  def addPatchedByRorVersionProperty(jarFile: File, rorVersion: String): Unit = {
    val tempJarFile = File(s"temp-${UUID.randomUUID()}.jar")
    val zipFile = new ZipFile(jarFile.toJava)
    val zipInput = new ZipInputStream(jarFile.newInputStream)
    val jarOutput = new JarOutputStream(tempJarFile.newOutputStream.buffered)
    try {
      zipFile
        .entries()
        .asIterator()
        .asScala
        .foreach { entry =>
          val name = entry.getName
          if (name.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
            // Modify manifest file by adding new property and copy the result to the temp jar
            val originalManifestContent = zipFile.getInputStream(entry).asString()
            val customPropertyLine = s"$patchedByRorVersionPropertyName: $rorVersion\n"
            val newManifestContent = originalManifestContent.replaceAll("(\\r\\n)+$", "") + "\n" + customPropertyLine
            val newEntry = new JarEntry(name)
            jarOutput.putNextEntry(newEntry)
            jarOutput.write(newManifestContent.getBytes("UTF-8"))
            jarOutput.closeEntry()
          } else {
            // Copy file other than manifest without changes to the temp jar
            val newEntry = new JarEntry(name)
            jarOutput.putNextEntry(newEntry)
            val in = zipFile.getInputStream(entry)
            in.transferTo(jarOutput)
            in.close()
            jarOutput.closeEntry()
          }
        }
      val copyOptions: File.CopyOptions = File.CopyOptions(overwrite = true)
      // Replace original jar with the newly created modified jar
      tempJarFile.moveTo(jarFile)(copyOptions)
    } finally {
      zipInput.close()
      jarOutput.close()
    }
  }

  def findPatchedFiles(esDirectory: EsDirectory): List[PatchedJarFile] = {
    val directory = File(esDirectory.modulesPath.wrapped)
    directory.walk().filter(_.name.endsWith(".jar")).toList.flatMap { jarFile =>
      Try(new ZipFile(jarFile.toJava)) match {
        case Success(zipFile) =>
          val zipInput = new ZipInputStream(jarFile.newInputStream)
          val patchedFiles =
            zipFile
              .entries()
              .asIterator()
              .asScala
              .find(_.getName.equalsIgnoreCase("META-INF/MANIFEST.MF"))
              .flatMap { manifestFile =>
                val manifestContentLines = zipFile.getInputStream(manifestFile).asString().linesIterator.toList.map(_.strip)
                val pattern: Regex = s"""$patchedByRorVersionPropertyName: (.*)""".r
                manifestContentLines.collectFirst { case pattern(rorVersion) => PatchedJarFile(jarFile.name, rorVersion) }
              }
          zipInput.close()
          patchedFiles
        case Failure(exception) =>
          Console.err.println(jarFile)
          Console.err.println(exception)
          None
      }
    }
  }

  final case class PatchedJarFile(name: String, patchedByRorVersion: String)

}
