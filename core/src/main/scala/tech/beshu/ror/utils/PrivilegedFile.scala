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
