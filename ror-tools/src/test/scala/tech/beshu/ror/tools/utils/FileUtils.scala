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
package tech.beshu.ror.tools.utils

import org.apache.commons.compress.archivers.tar.TarFile

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

object FileUtils {

  def calculateHash(dir: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")

    val fileList = Files.walk(dir)
      .filter(Files.isRegularFile(_))
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
    for (entry <- tarFile.getEntries.asScala) {
      val path = outputPath.resolve(entry.getName)
      if (entry.isDirectory) {
        Files.createDirectories(path)
      } else {
        Files.createDirectories(path.getParent)
        Files.copy(tarFile.getInputStream(entry), path)
      }
    }
  }

}
