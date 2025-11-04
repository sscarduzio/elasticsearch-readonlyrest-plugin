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
package tech.beshu.ror.tools.core.patches.internal.modifiers

import better.files.File
import tech.beshu.ror.tools.core.utils.FileUtils.*

import java.io.{ByteArrayInputStream, InputStream}
import java.net.URI
import java.nio.file.*
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions
import scala.util.Using

private[patches] abstract class BytecodeJarModifier(debugEnabled: Boolean = false)
  extends FileModifier with AsmDebug {

  protected def addNewFileToJar(jar: File,
                                filePathString: String,
                                content: Array[Byte]): Unit = {
    val originalPermsAndOwner = jar.getFilePermissionsAndOwner
    if (debugEnabled) debug(content)
    val env = Map("create" -> "true").asJava
    val uri = URI.create("jar:" + jar.toJava.toURI)

    Using.resource(FileSystems.newFileSystem(uri, env)) { zipfs =>
      val path = zipfs.getPath(filePathString)
      Option(path.getParent).foreach(p => Files.createDirectories(p))
      Files.write(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }
    jar.setFilePermissionsAndOwner(originalPermsAndOwner)
  }

  protected def modifyFileInJar(jar: File,
                                filePathString: String,
                                processFileContent: InputStream => Array[Byte]): Unit = {
    val originalFilePermissionsAndOwner = jar.getFilePermissionsAndOwner
    val modifiedFileContent = loadAndProcessFileFromJar(
      jar = jar,
      filePathString = filePathString,
      processFileContent = processFileContent
    )
    updateFileInJar(
      jar = jar,
      destinationPathSting = filePathString,
      newContent = modifiedFileContent
    )
    jar.setFilePermissionsAndOwner(originalFilePermissionsAndOwner)
  }

  private def loadAndProcessFileFromJar(jar: File,
                                        filePathString: String,
                                        processFileContent: InputStream => Array[Byte]): Array[Byte] = {
    Option(new JarFile(jar.toJava))
      .flatMap { jarFile =>
        try {
          findFileInJar(jarFile, filePathString)
            .map(processFileContent)
        } finally {
          jarFile.close()
        }
      }
      .getOrElse {
        throw new IllegalStateException(s"Cannot find $filePathString in ${jar.toString}")
      }
  }

  private def updateFileInJar(jar: File,
                              destinationPathSting: String,
                              newContent: Array[Byte]): Unit = {
    if (debugEnabled) debug(newContent)
    Option(FileSystems.newFileSystem(
      URI.create("jar:" + jar.toJava.toURI),
      Map("create" -> "true").asJava
    )) map { zipfs =>
      try {
        Files.copy(
          new ByteArrayInputStream(newContent),
          zipfs.getPath(destinationPathSting),
          StandardCopyOption.REPLACE_EXISTING,
        )
      } finally {
        zipfs.close()
      }
    }
  }

  private def findFileInJar(jarFile: JarFile, filePathString: String) = {
    jarFile
      .entries().asScala
      .find { entry => filePathString == entry.getName }
      .map(jarFile.getInputStream)
  }

  // for manual tests purposes
  def main(args: Array[String]): Unit = {
    val pathToJar = args(0)
    apply(File(pathToJar))
  }
}
