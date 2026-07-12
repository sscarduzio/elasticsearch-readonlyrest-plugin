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
import tech.beshu.ror.utils.misc.ScalaUtils

import java.io.{BufferedInputStream, FileOutputStream}
import java.nio.file.{Files, StandardCopyOption}
import java.util.zip.ZipInputStream
import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.util.control.NonFatal
import scala.util.{Try, Using}

object WindowsEsDirectoryManager extends LazyLogging {

  def downloadUrl(esVersion: String): String =
    s"https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-$esVersion-windows-x86_64.zip"

  def basePath: os.Path = {
    // Sharded runs: separate install/data trees per shard JVM — the es_<cluster>_<node> dirs are
    // name-keyed and would collide across concurrently-running shards on one host.
    val shard = Integer.getInteger("ror.shard.index", -1)
    if (shard >= 0) os.pwd / s"windows-es-shard-$shard" else os.pwd / "windows-es"
  }

  // The ES zip download cache is SHARED across shards (read-only after download) — only the
  // unpacked per-node install/data trees need shard separation. Without this every shard
  // re-downloaded the distribution (~minutes each on the first suite).
  def downloadsPath: os.Path =
    os.pwd / "windows-es" / "downloads"

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
      downloadVerifiedWithRetry(esVersion, dest, attempts = 3)
    }
  }

  def cleanDownloadsDirectory(): Unit = {
    logger.info(s"Removing all files from Windows ES downloads directory")
    os.remove.all(downloadsPath)
  }

  private def downloadVerifiedWithRetry(esVersion: String, dest: os.Path, attempts: Int): Unit = {
    // Download to a temp name, sha512-verify, then move into place ATOMICALLY — a reader never sees
    // a partial file and a failed attempt leaves no poisoned cache entry.
    val tmp = dest / os.up / s"${dest.last}.part"
    try {
      ScalaUtils.retry(
        times = attempts,
        cleanBeforeRetrying = Try(os.remove(tmp)),
        delayBetweenRetries = 5.seconds
      ) {
        doDownloadEsZipFileWithProgress(downloadUrl(esVersion), tmp)
        val expected = expectedSha512(esVersion)
        if (!sha512Matches(tmp, expected)) {
          throw new IllegalStateException(
            s"Downloaded ES $esVersion zip fails sha512 verification (expected $expected)"
          )
        }
        os.move(tmp, dest, replaceExisting = true, atomicMove = true)
        logger.info(s"ES $esVersion for Windows downloaded and sha512-verified")
      }
    } catch {
      case NonFatal(e) =>
        // Clean up the .part file on final failure — retry() only cleans before retries, not on exhaustion.
        Try(os.remove(tmp))
        throw e
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
    ScalaUtils.sha512(better.files.File(file.toNIO)) == expected
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
    // Unzip ONCE per ES version into a shared template (file-lock guarded across shard JVMs),
    // then per-node robocopy: single-threaded Java unzip of the ~600MB distribution PER NODE was
    // one of the dominant Windows costs (every stream write also pays the antivirus filter).
    val targetDir = esPath(config.clusterName, config.nodeName)
    os.remove.all(targetDir)
    val template = templatePath(esVersion)
    ensureTemplate(esVersion, template)
    logger.info(s"Copying ES template $template -> $targetDir")
    os.makeDir.all(targetDir / os.up)
    // Native multithreaded copy; robocopy exit codes 0-7 mean success.
    val rc = os
      .proc("robocopy", template.toString, targetDir.toString, "/E", "/MT:16", "/NFL", "/NDL", "/NJH", "/NJS", "/NP")
      .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
      .exitCode
    if (rc >= 8) throw new IllegalStateException(s"robocopy of ES template failed with exit code $rc")
    logger.info(s"ES ready at $targetDir")
  }

  private def templatePath(esVersion: String): os.Path =
    os.pwd / "windows-es" / "templates" / s"es-$esVersion"

  private def ensureTemplate(esVersion: String, template: os.Path): Unit = {
    val marker = template / ".unzip-complete"
    if (!os.exists(marker)) {
      // Cross-shard-JVM lock: only one shard unzips; the rest wait then reuse.
      val lockDir = template / os.up
      os.makeDir.all(lockDir)
      Using.resource(
        java.nio.channels.FileChannel.open(
          (lockDir / s"es-$esVersion.lock").toNIO,
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.WRITE
        )
      ) { channel =>
        val lock = channel.lock()
        try {
          if (!os.exists(marker)) {
            logger.info(s"Unzipping ES $esVersion into shared template")
            os.remove.all(template)
            os.makeDir.all(template)
            val zipFile = zipFilePath(esVersion)
            Using.resource(new ZipInputStream(Files.newInputStream(zipFile.toNIO))) { zis =>
              LazyList
                .continually(zis.getNextEntry)
                .takeWhile(_ != null)
                .foreach { entry =>
                  val relPath = os.RelPath(entry.getName)
                  // Drop the first path segment (without this step, the zip file name is part of the unzipped directory path)
                  val strippedPath =
                    if (relPath.segments.nonEmpty) os.RelPath.fromStringSegments(relPath.segments.tail.toArray)
                    else relPath
                  val newPath = template / strippedPath
                  if (entry.isDirectory) {
                    os.makeDir.all(newPath)
                  } else {
                    os.makeDir.all(newPath / os.up)
                    Files.copy(zis, newPath.toNIO, StandardCopyOption.REPLACE_EXISTING)
                  }
                }
            }
            os.write(marker, "")
            logger.info(s"ES $esVersion template ready")
          }
        } finally {
          lock.release()
        }
      }
    }
  }

}
