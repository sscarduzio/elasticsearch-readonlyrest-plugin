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

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{AccessDeniedException, FileVisitResult, Files, Path as JPath, SimpleFileVisitor}
import java.util.UUID
import java.util.jar.{JarEntry, JarFile, JarOutputStream}
import scala.collection.mutable.ListBuffer
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
  // Left: the folders that the current user cannot read. Then nobody can tell if a jar in them is patched.
  def findPatchedFiles(esDirectory: EsDirectory): Either[List[os.Path], List[PatchedJarFile]] = {
    val collector = new JarFilesCollector
    List(esDirectory.libPath, esDirectory.modulesPath).foreach { directory =>
      Files.walkFileTree(directory.toNIO, collector)
    }
    if (collector.inaccessibleFolders.nonEmpty) Left(collector.inaccessibleFolders.toList.map(os.Path(_)))
    else Right(collector.jars.toList.flatMap(findPatchedJarFile))
  }

  private def findPatchedJarFile(jar: JPath): Option[PatchedJarFile] = {
    Using(new JarFile(jar.toFile)) { jarFile =>
      val rorVersion = Option(jarFile.getManifest.getMainAttributes.getValue(patchedByRorVersionPropertyName))
      rorVersion.map(PatchedJarFile(jar.getFileName.toString, _))
    }.toOption.flatten
  }

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

  final case class PatchedJarFile(name: String, patchedByRorVersion: String)

  // Collects the jar files, and the folders that the current user cannot read. Other I/O errors stop the walk.
  private final class JarFilesCollector extends SimpleFileVisitor[JPath] {
    val jars: ListBuffer[JPath] = ListBuffer.empty
    val inaccessibleFolders: ListBuffer[JPath] = ListBuffer.empty

    override def visitFile(file: JPath, attributes: BasicFileAttributes): FileVisitResult = {
      if (file.getFileName.toString.endsWith(".jar")) jars += file
      FileVisitResult.CONTINUE
    }

    override def visitFileFailed(file: JPath, exception: IOException): FileVisitResult = exception match {
      case _: AccessDeniedException =>
        inaccessibleFolders += file
        FileVisitResult.CONTINUE
      case other => throw other
    }

  }

}
