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
package tech.beshu.ror.tools.core.utils

import better.files.File

import java.nio.file.attribute.{DosFileAttributeView, PosixFilePermission, UserPrincipal}
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions

object FileUtils {

  def calculateFileHash(filePath: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(filePath.toString.getBytes) // Include file path in hash
    digest.update(Files.readAllBytes(filePath)) // Include file contents in hash
    digest.digest.map("%02x".format(_)).mkString
  }

  extension (file: File)
    def setFilePermissionsAndOwner(filePermissionsAndOwner: FilePermissionsAndOwner): File = {
      filePermissionsAndOwner match {
        case metadata: FilePermissionsAndOwnerImpl =>
          Files.setOwner(file.path, metadata.owner)
          setOriginalPermissions(file.path, metadata.filePermissions)
          file
      }
    }

    def setFilePermissionsAndOwnerCopiedFrom(originalFile: File): File = {
      file.setFilePermissionsAndOwner(originalFile.getFilePermissionsAndOwner)
    }

    def getFilePermissionsAndOwner: FilePermissionsAndOwner = {
      FilePermissionsAndOwnerImpl(
        getOriginalPermissions(file.path),
        Files.getOwner(file.path),
      )
    }

  given osPathToFile: Conversion[os.Path, File] with
    def apply(path: os.Path): File = File(path.toString)

  given javaFileToFile: Conversion[java.io.File, File] with
    def apply(jFile: java.io.File): File = File(jFile.toPath)

  sealed trait FilePermissionsAndOwner

  // The implementation details of FilePermissionsAndOwner should not leak outside of this file
  private final case class FilePermissionsAndOwnerImpl(filePermissions: Any,
                                                       owner: UserPrincipal) extends FilePermissionsAndOwner

  private def getOriginalPermissions(jarPath: Path): Any = {
    if (isWindows) {
      Files.getFileAttributeView(jarPath, classOf[DosFileAttributeView])
    } else {
      Files.getPosixFilePermissions(jarPath)
    }
  }

  private def setOriginalPermissions(jarPath: Path, permissions: Any): Unit = {
    if (isWindows) {
      val view = permissions.asInstanceOf[DosFileAttributeView]
      view.setReadOnly(view.readAttributes().isReadOnly)
      view.setHidden(view.readAttributes().isHidden)
      view.setArchive(view.readAttributes().isArchive)
      view.setSystem(view.readAttributes().isSystem)
    } else {
      Files.setPosixFilePermissions(jarPath, permissions.asInstanceOf[java.util.Set[PosixFilePermission]])
    }
  }

  private def isWindows = {
    System.getProperties.stringPropertyNames().asScala
      .find { name =>
        // I have no idea why name == "os.name" doesn't work!
        name.length == 7 && name.indexOf("o") == 0 && name.endsWith("s.name")
      }
      .flatMap { osNamePropName =>
        Option(System.getProperty(osNamePropName))
      } match
      case Some(osName) => osName.toLowerCase.contains("win")
      case None => false
  }
}
