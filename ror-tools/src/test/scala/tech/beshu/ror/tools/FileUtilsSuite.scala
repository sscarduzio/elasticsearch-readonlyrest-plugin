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
package tech.beshu.ror.tools

import better.files.File
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.tools.core.utils.FileUtils.{getFilePermissionsAndOwner, setFilePermissionsAndOwner}
import tech.beshu.ror.utils.misc.OsUtils

import java.nio.file.attribute.{DosFileAttributeView, DosFileAttributes}
import java.nio.file.{Files, StandardCopyOption}

class FileUtilsSuite extends AnyWordSpec with Matchers {

  "FileUtils" should {
    "restore the DOS flags of a file that was replaced" in {
      // The DOS flags are used only on Windows
      OsUtils.runOnlyOnWindows {
        File.usingTemporaryDirectory() { directory =>
          val file = (directory / "file").createFile()
          // Each flag is different from the flags of a new file
          val originalFlags = DosFlags(readOnly = true, hidden = true, archive = false, system = true)
          try {
            setDosFlags(file, originalFlags)
            val originalPermissionsAndOwner = file.getFilePermissionsAndOwner

            // Windows does not replace a read-only file
            dosView(file).setReadOnly(false)
            val newFile = (directory / "new-file").createFile()
            Files.move(newFile.path, file.path, StandardCopyOption.REPLACE_EXISTING)
            file.setFilePermissionsAndOwner(originalPermissionsAndOwner)

            dosFlagsOf(file) should equal(originalFlags)
          } finally {
            // Windows does not delete a read-only file
            dosView(file).setReadOnly(false)
          }
        }
      }
    }
  }

  private final case class DosFlags(readOnly: Boolean, hidden: Boolean, archive: Boolean, system: Boolean)

  private def dosView(file: File): DosFileAttributeView =
    Files.getFileAttributeView(file.path, classOf[DosFileAttributeView])

  private def setDosFlags(file: File, flags: DosFlags): Unit = {
    val view = dosView(file)
    view.setHidden(flags.hidden)
    view.setArchive(flags.archive)
    view.setSystem(flags.system)
    view.setReadOnly(flags.readOnly)
  }

  private def dosFlagsOf(file: File): DosFlags = {
    val attributes = Files.readAttributes(file.path, classOf[DosFileAttributes])
    DosFlags(attributes.isReadOnly, attributes.isHidden, attributes.isArchive, attributes.isSystem)
  }

}
