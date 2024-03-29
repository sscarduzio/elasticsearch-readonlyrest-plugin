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

import java.io.{ByteArrayInputStream, File, InputStream}
import java.net.URI
import java.nio.file.{FileSystems, Files, Paths, StandardCopyOption}
import java.util.jar.JarFile
import scala.jdk.CollectionConverters._

private [patches] abstract class BytecodeJarModifier(debugEnabled: Boolean = false)
  extends FileModifier with AsmDebug {

  protected def loadAndProcessFileFromJar(jar: File,
                                          classFileName: String,
                                          processFileContent: InputStream => Array[Byte]): Array[Byte] = {
    Option(new JarFile(jar))
      .flatMap { jarFile =>
        try {
          findFileInJar(jarFile, classFileName)
            .map(processFileContent)
        } finally {
          jarFile.close()
        }
      }
      .getOrElse {
        throw new IllegalStateException(s"Cannot find ${classFileName}.class in ${jar.toString}")
      }
  }

  protected def updateFileInJar(jar: File,
                                destinationPathSting: String,
                                newContent: Array[Byte]): Unit = {
    if (debugEnabled) debug(newContent)
    Option(FileSystems.newFileSystem(
      URI.create("jar:" + jar.toURI),
      Map("create" -> "true").asJava
    )) map { zipfs =>
      try {
        Files.copy(
          new ByteArrayInputStream(newContent),
          zipfs.getPath(destinationPathSting),
          StandardCopyOption.REPLACE_EXISTING
        )
      } finally {
        zipfs.close()
      }
    }
  }

  private def findFileInJar(jarFile: JarFile, classFileName: String) = {
    jarFile
      .entries().asScala
      .find { entry => s"$classFileName.class" == entry.getName }
      .map(jarFile.getInputStream)
  }

  // for manual tests purposes
  def main(args: Array[String]): Unit = {
    val pathToJar = args(0)
    val jar = Paths.get(pathToJar).toFile
    apply(jar)
  }
}
