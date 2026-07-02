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
import scala.util.{Failure, Success, Try, Using}

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
    if (dest.toIO.exists() && sha512Matches(dest, expectedSha512(esVersion))) {
      logger.info(s"ES $esVersion for Windows already downloaded (sha512 verified)")
    } else {
      // Verifying the cache too: the old exists()-only check reused a truncated download forever
      // ("Unexpected end of ZLIB input stream" aborting whole legs).
      if (dest.toIO.exists()) {
        logger.warn(s"Cached ES $esVersion zip fails sha512 verification — deleting and re-downloading")
        os.remove(dest)
      }
      downloadVerifiedWithRetry(esVersion, dest, attemptsLeft = 3)
    }
  }

  def cleanDownloadsDirectory(): Unit = {
    logger.info(s"Removing all files from Windows ES downloads directory")
    os.remove.all(downloadsPath)
  }

  @scala.annotation.tailrec
  private def downloadVerifiedWithRetry(esVersion: String, dest: os.Path, attemptsLeft: Int): Unit = {
    // Download to a temp name, sha512-verify, then move into place ATOMICALLY — a reader never sees
    // a partial file and a failed attempt leaves no poisoned cache entry.
    val tmp = dest / os.up / s"${dest.last}.part"
    Try {
      doDownloadEsZipFileWithProgress(downloadUrl(esVersion), tmp)
      val expected = expectedSha512(esVersion)
      if (!sha512Matches(tmp, expected)) {
        throw new IllegalStateException(s"Downloaded ES $esVersion zip fails sha512 verification (expected $expected)")
      }
      os.move(tmp, dest, replaceExisting = true, atomicMove = true)
      logger.info(s"ES $esVersion for Windows downloaded and sha512-verified")
    } match {
      case Success(())                     => ()
      case Failure(ex) if attemptsLeft > 1 =>
        logger.warn(s"ES $esVersion download failed (${ex.getMessage}) — retrying (${attemptsLeft - 1} attempts left)")
        Try(os.remove(tmp))
        Thread.sleep(5000)
        downloadVerifiedWithRetry(esVersion, dest, attemptsLeft - 1)
      case Failure(ex) =>
        Try(os.remove(tmp))
        throw ex
    }
  }

  // Elastic publishes `<artifact-url>.sha512` ("<hash>  <filename>") for every artifact.
  private def expectedSha512(esVersion: String): String = {
    val connection = java.net.URI.create(s"${downloadUrl(esVersion)}.sha512").toURL.openConnection()
    connection.setConnectTimeout(30_000)
    connection.setReadTimeout(60_000)
    Using.resource(scala.io.Source.fromInputStream(connection.getInputStream)) {
      _.mkString.trim.split("\\s+").head.toLowerCase
    }
  }

  private def sha512Matches(file: os.Path, expected: String): Boolean = {
    val md = java.security.MessageDigest.getInstance("SHA-512")
    Using.resource(Files.newInputStream(file.toNIO)) { in =>
      val buffer = new Array[Byte](64 * 1024)
      Iterator.continually(in.read(buffer)).takeWhile(_ != -1).foreach(md.update(buffer, 0, _))
    }
    md.digest().map("%02x".format(_)).mkString == expected
  }

  private def doDownloadEsZipFileWithProgress(url: String, dest: os.Path): Unit = {
    logger.info(s"Downloading ES for Windows from $url to $dest")

    val connection = java.net.URI.create(url).toURL.openConnection()
    connection.setConnectTimeout(30_000)
    connection.setReadTimeout(120_000)
    val fileSize = connection.getContentLength
    logger.info(s"Zip file size: ${fileSize / 1024 / 1024} MB")

    // Fixed 64KB buffer (the old whole-file-sized buffer also NPE'd on a missing Content-Length).
    val buffer = new Array[Byte](64 * 1024)
    val written = Using.resources(
      new BufferedInputStream(connection.getInputStream),
      new FileOutputStream(dest.toIO)
    ) { (in, out) =>
      Iterator
        .continually(in.read(buffer))
        .takeWhile(_ != -1)
        .foldLeft(0L) { (bytesRead, read) =>
          out.write(buffer, 0, read)
          val total = bytesRead + read
          displayProgressBar(bytesRead, total, fileSize)
          total
        }
    }
    // A silently-dropped connection ends the stream without error — catch truncation precisely.
    if (fileSize > 0 && written != fileSize) {
      throw new IllegalStateException(s"Truncated download: got $written of $fileSize bytes")
    }
  }

  private def displayProgressBar(previousBytesRead: Long, bytesRead: Long, fileSize: Int): Unit = {
    val previousPercent = (100.0 * previousBytesRead / fileSize).toInt
    val percent = (100.0 * bytesRead / fileSize).toInt
    if (previousPercent != percent && percent % 4 == 0) {
      logger.info(
        s"Download progress: [ ${List.fill(percent / 2)("#").mkString}${List.fill((100 - percent) / 2)(" ").mkString} ] $percent%"
      )
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
