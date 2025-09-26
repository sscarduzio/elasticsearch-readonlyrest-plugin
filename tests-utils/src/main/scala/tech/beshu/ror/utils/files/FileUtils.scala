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
package tech.beshu.ror.utils.files

import org.apache.commons.compress.archivers.tar.TarFile

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

object FileUtils {

  def calculateHash(dir: Path, excludedFiles: List[String] = Nil): String = {
    val digest = MessageDigest.getInstance("SHA-256")

    val fileList = Files.walk(dir)
      .filter(Files.isRegularFile(_))
      .filter(f => !excludedFiles.exists(excluded => f.toFile.toString.endsWith(excluded)))
      .iterator()
      .asScala
      .toSeq
      .sorted

    val finalDigest = fileList.foldLeft(digest) { (d, file) =>
      d.update(file.toString.getBytes) // Include file path in hash
      d.update(Files.readAllBytes(file)) // Include file contents in hash
      d
    }

    finalDigest.digest.map("%02x".format(_)).mkString
  }

  def unTar(tarPath: Path, outputPath: Path): Unit = {
    val tarFile = new TarFile(tarPath.toFile)
    try {
      for (entry <- tarFile.getEntries.asScala) {
        val entryName = entry.getName
        val path = outputPath.resolve(entryName).normalize()
        if (entryName.contains("..") || !path.startsWith(outputPath)) {
          throw new SecurityException(s"Path traversal attempt detected: $entryName")
        }
        if (entry.isDirectory) {
          Files.createDirectories(path)
        } else {
          Files.createDirectories(path.getParent)
          Files.copy(tarFile.getInputStream(entry), path,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
      }
    } finally {
      tarFile.close()
    }
  }

}
