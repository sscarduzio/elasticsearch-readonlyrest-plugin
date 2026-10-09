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

import java.nio.file.attribute.{
  DosFileAttributeView,
  DosFileAttributes,
  GroupPrincipal,
  PosixFileAttributeView,
  PosixFileAttributes,
  PosixFilePermission,
  UserPrincipal
}
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

  extension (file: File) {

    def setFilePermissionsAndOwner(filePermissionsAndOwner: FilePermissionsAndOwner): File = {
      filePermissionsAndOwner match {
        case metadata: OriginalFilePermissionsAndOwner =>
          Files.setOwner(file.path, metadata.owner)
          metadata.group.foreach(setGroup(file.path, _))
          setOriginalPermissions(file.path, metadata.filePermissions)
          file
      }
    }

    def setFilePermissionsAndOwnerCopiedFrom(originalFile: File): File = {
      file.setFilePermissionsAndOwner(originalFile.getFilePermissionsAndOwner)
    }

    def getFilePermissionsAndOwner: FilePermissionsAndOwner = {
      OriginalFilePermissionsAndOwner(
        getOriginalPermissions(file.path),
        Files.getOwner(file.path),
        getGroup(file.path),
      )
    }

  }

  given osPathToFile: Conversion[os.Path, File] with
    def apply(path: os.Path): File = File(path.toString)

  given javaFileToFile: Conversion[java.io.File, File] with
    def apply(jFile: java.io.File): File = File(jFile.toPath)

  sealed trait FilePermissionsAndOwner

  // The implementation details of FilePermissionsAndOwner should not leak outside of this file
  private final case class OriginalFilePermissionsAndOwner(
      filePermissions: FilePermissions,
      owner: UserPrincipal,
      group: Option[GroupPrincipal]
  ) extends FilePermissionsAndOwner

  private sealed trait FilePermissions

  private object FilePermissions {
    final case class Posix(permissions: java.util.Set[PosixFilePermission]) extends FilePermissions

    // Windows ACLs are not copied. The archive flag is not copied either: Windows sets it when the
    // content changes, and backup tools use it to find changed files.
    final case class Dos(readOnly: Boolean, hidden: Boolean, system: Boolean) extends FilePermissions
  }

  private def getGroup(path: Path): Option[GroupPrincipal] = {
    if (isWindows) None
    else Some(Files.readAttributes(path, classOf[PosixFileAttributes]).group())
  }

  private def setGroup(path: Path, group: GroupPrincipal): Unit = {
    Files.getFileAttributeView(path, classOf[PosixFileAttributeView]).setGroup(group)
  }

  private def getOriginalPermissions(path: Path): FilePermissions = {
    if (isWindows) {
      val attributes = Files.readAttributes(path, classOf[DosFileAttributes])
      FilePermissions.Dos(attributes.isReadOnly, attributes.isHidden, attributes.isSystem)
    } else {
      FilePermissions.Posix(Files.getPosixFilePermissions(path))
    }
  }

  private def setOriginalPermissions(path: Path, permissions: FilePermissions): Unit = {
    permissions match {
      case FilePermissions.Posix(posixPermissions) =>
        Files.setPosixFilePermissions(path, posixPermissions)
      case FilePermissions.Dos(readOnly, hidden, system) =>
        val view = Files.getFileAttributeView(path, classOf[DosFileAttributeView])
        view.setHidden(hidden)
        view.setSystem(system)
        // The read-only flag goes last, because a read-only file can block the other changes
        view.setReadOnly(readOnly)
    }
  }

  private def isWindows = {
    System.getProperties
      .stringPropertyNames()
      .asScala
      .find { name =>
        // I have no idea why name == "os.name" doesn't work!
        name.length == 7 && name.indexOf("o") == 0 && name.endsWith("s.name")
      }
      .flatMap { osNamePropName =>
        Option(System.getProperty(osNamePropName))
      } match
      case Some(osName) => osName.toLowerCase.contains("win")
      case None         => false
  }

}
