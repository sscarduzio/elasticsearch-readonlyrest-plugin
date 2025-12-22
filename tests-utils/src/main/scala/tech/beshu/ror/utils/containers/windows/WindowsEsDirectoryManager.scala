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
package tech.beshu.ror.utils.containers.windows

import com.typesafe.scalalogging.LazyLogging
import tech.beshu.ror.utils.containers.images.Elasticsearch.Config

import java.io.{BufferedInputStream, FileOutputStream}
import java.nio.file.{Files, StandardCopyOption}
import java.util.zip.ZipInputStream
import scala.language.postfixOps
import scala.util.Using

object WindowsEsDirectoryManager extends LazyLogging {

  def downloadUrl(esVersion: String): String =
    s"https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-$esVersion-windows-x86_64.zip"

  def basePath: os.Path =
    os.pwd / "windows-es"

  def downloadsPath: os.Path =
    basePath / "downloads"

  def zipFilePath(esVersion: String): os.Path =
    downloadsPath / s"elasticsearch-$esVersion.zip"

  def esPath(clusterName: String, nodeName: String): os.Path =
    basePath / s"es_${clusterName}_${nodeName}"

  def configPath(clusterName: String, nodeName: String): os.Path =
    esPath(clusterName, nodeName) / "config"

  def configFilePath(clusterName: String, nodeName: String): os.Path =
    configPath(clusterName, nodeName) / "elasticsearch.yml"

  def binPath(clusterName: String, nodeName: String): os.Path =
    esPath(clusterName, nodeName) / "bin"

  def downloadEsZipFileWithProgress(esVersion: String): Unit = {
    os.makeDir.all(downloadsPath)
    val dest = zipFilePath(esVersion)
    logger.info(s"Checking if ES $esVersion for Windows is already downloaded")
    if (!dest.toIO.exists()) {
      logger.info(s"ES $esVersion for Windows not yet downloaded, downloading")
      doDownloadEsZipFileWithProgress(esVersion)
    } else {
      logger.info(s"ES $esVersion for Windows already downloaded")
    }
  }

  def cleanDownloadsDirectory(): Unit = {
    logger.info(s"Removing all files from Windows ES downloads directory")
    os.remove.all(downloadsPath)
  }

  private def doDownloadEsZipFileWithProgress(esVersion: String): Unit = {
    val url = downloadUrl(esVersion)
    val dest = zipFilePath(esVersion)

    logger.info(s"Downloading ES for Windows from $url to $dest")

    val connection = java.net.URI.create(url).toURL.openConnection()
    val fileSize = connection.getContentLength
    val fileSizeMb = fileSize / 1024 / 1024

    logger.info(s"Zip file size: $fileSizeMb MB")

    val buffer = new Array[Byte](fileSize)
    Using.resources(
      new BufferedInputStream(connection.getInputStream),
      new FileOutputStream(dest.toIO)
    ) { (in, out) =>
      Iterator
        .continually(in.read(buffer))
        .takeWhile(_ != -1)
        .foldLeft(0) { (bytesRead, read) =>
          out.write(buffer, 0, read)
          val total = bytesRead + read
          displayProgressBar(bytesRead, total, fileSize)
          total
        }
    }
  }

  private def displayProgressBar(previousBytesRead: Int, bytesRead: Int, fileSize: Int): Unit = {
    val previousPercent = (100.0 * previousBytesRead / fileSize).toInt
    val percent = (100.0 * bytesRead / fileSize).toInt
    if (previousPercent != percent && percent % 4 == 0) {
      logger.info(s"Download progress: [ ${List.fill(percent / 2)("#").mkString}${List.fill((100 - percent) / 2)(" ").mkString} ] $percent%")
    }
  }

  def unzipEs(esVersion: String, config: Config): Unit = {
    logger.info(s"Unzipping ES")
    val zipFile = zipFilePath(esVersion)
    val targetDir = esPath(config.clusterName, config.nodeName)
    os.remove.all(targetDir)
    os.makeDir.all(targetDir)
    Using.resource(
      new ZipInputStream(Files.newInputStream(zipFile.toNIO))
    ) { zis =>
      LazyList
        .continually(zis.getNextEntry)
        .takeWhile(_ != null)
        .foreach { entry =>
          val relPath = os.RelPath(entry.getName)

          // Drop the first path segment (without this step, the zip file name is part of the unzipped directory path)
          val strippedPath =
            if (relPath.segments.nonEmpty) os.RelPath.fromStringSegments(relPath.segments.tail.toArray)
            else relPath

          val newPath = targetDir / strippedPath
          if (entry.isDirectory) {
            os.makeDir.all(newPath)
          } else {
            os.makeDir.all(newPath / os.up)
            Files.copy(zis, newPath.toNIO, StandardCopyOption.REPLACE_EXISTING)
          }
        }
    }
    logger.info(s"Unzipped ES")
  }

}
