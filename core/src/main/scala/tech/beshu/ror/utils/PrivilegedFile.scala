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
package tech.beshu.ror.utils

import java.io.FileReader
import java.nio.file.Path
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

import better.files.{Dispose, File}

class PrivilegedFile(file: File) {
  def exists: Boolean = {
    doPrivileged(file.exists)
  }
  
  def fileReader: Dispose[FileReader] = {
    doPrivileged(file.fileReader)
  }

  def contentAsString: String = {
    doPrivileged(file.contentAsString)
  }

  def pathAsString: String = {
    file.pathAsString
  }
}

object PrivilegedFile {
  def apply(file: File): PrivilegedFile = new PrivilegedFile(file)

  def apply(path: Path): PrivilegedFile = new PrivilegedFile(File(path))

  def apply(path: String): PrivilegedFile = new PrivilegedFile(File(path))
}
