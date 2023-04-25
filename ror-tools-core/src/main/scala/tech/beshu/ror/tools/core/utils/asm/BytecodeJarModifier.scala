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
package tech.beshu.ror.tools.core.utils.asm

import java.io.{ByteArrayInputStream, File, InputStream}
import java.net.URI
import java.nio.file.{FileSystems, Files, StandardCopyOption}
import java.util.jar.JarFile
import scala.jdk.CollectionConverters._

abstract class BytecodeJarModifier {

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
      .find { entry =>
        val name = entry.getName
        val classSuffix = ".class"
        name.startsWith(classFileName) && name.endsWith(classSuffix) && name.length == (classFileName.length + classSuffix.length)
      }
      .map(jarFile.getInputStream)
  }
}
