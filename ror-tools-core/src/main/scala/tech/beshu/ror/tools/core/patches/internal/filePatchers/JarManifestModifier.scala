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

import java.io.FileOutputStream
import java.nio.file.Files
import java.util.jar.{JarFile, JarOutputStream, Manifest}
import scala.util.{Failure, Success, Try}

object JarManifestModifier {

  val patchedByRorVersionPropertyName = "Patched-By-Ror-Version"

  def addPatchedByRorVersionProperty(jarFile: File, rorVersion: String): Unit = {
    val originalJar = new JarFile(jarFile.toJava)
    val manifest = new Manifest(originalJar.getManifest)
    manifest.getMainAttributes.putValue(patchedByRorVersionPropertyName, rorVersion)
    val tempJarFile = Files.createTempFile("updated-", ".jar").toFile
    val jos = new JarOutputStream(new FileOutputStream(tempJarFile), manifest)
    val entries = originalJar.entries()
    while (entries.hasMoreElements) {
      val entry = entries.nextElement()
      if (!entry.getName.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
        val newEntry = new java.util.zip.ZipEntry(entry.getName)
        jos.putNextEntry(newEntry)
        val is = originalJar.getInputStream(entry)
        is.transferTo(jos)
        is.close()
        jos.closeEntry()
      }
    }
    jos.close()
    originalJar.close()
    Files.move(tempJarFile.toPath, jarFile.toJava.toPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
  }

  def findPatchedFiles(esDirectory: EsDirectory): List[PatchedJarFile] = {
    val directory = File(esDirectory.modulesPath.wrapped)
    directory.walk().filter(_.name.endsWith(".jar")).toList.flatMap { file =>
      Try(new JarFile(file.toJava)) match {
        case Success(jarFile) =>
          val rorVersion = Option(jarFile.getManifest.getMainAttributes.getValue(patchedByRorVersionPropertyName))
          rorVersion.map(PatchedJarFile(file.name, _))
        case Failure(exception) =>
          Console.err.println(file)
          Console.err.println(exception)
          None
      }
    }
  }

  final case class PatchedJarFile(name: String, patchedByRorVersion: String)

}
