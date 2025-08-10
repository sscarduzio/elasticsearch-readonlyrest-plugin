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

import better.files.File
import com.typesafe.scalalogging.LazyLogging
import os.*
import tech.beshu.ror.utils.containers.images.Elasticsearch
import tech.beshu.ror.utils.containers.images.Elasticsearch.Config

import java.io.{BufferedInputStream, FileOutputStream}
import java.nio.file.{Files, StandardCopyOption}
import java.util.zip.ZipInputStream
import scala.language.postfixOps
import scala.sys.process.*

object WindowsElasticsearchSetup extends LazyLogging {

  def downloadUrl(esVersion: String): String =
    s"https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-$esVersion-windows-x86_64.zip"

  def basePath: os.Path =
    os.pwd / "windows-es"

  def zipFilePath(esVersion: String): os.Path =
    basePath / "downloads" / s"elasticsearch-$esVersion.zip"

  def esPath(clusterName: String, nodeName: String): os.Path =
    basePath / s"es_${clusterName}_${nodeName}"

  def configPath(clusterName: String, nodeName: String): os.Path =
    esPath(clusterName, nodeName) / "config"

  def configFilePath(clusterName: String, nodeName: String): os.Path =
    configPath(clusterName, nodeName) / "elasticsearch.yml"

  def binPath(clusterName: String, nodeName: String): os.Path =
    esPath(clusterName, nodeName) / "bin"

  def prepareAndStartEsForWindows(elasticsearch: Elasticsearch): Unit = {
    downloadEsZipFileWithProgress(elasticsearch.esVersion)
    unzip(elasticsearch.esVersion, elasticsearch.config)
    replaceConfigFile(elasticsearch)
    startElasticsearch(elasticsearch.config)
  }

  private def downloadEsZipFileWithProgress(esVersion: String): Unit = {
    val dest = zipFilePath(esVersion)
    logger.info(s"Checking if ES $esVersion for Windows is already downloaded")
    if (!dest.toIO.exists()) {
      logger.info(s"ES $esVersion for Windows was not already downloaded, downloading")
      doDownloadEsZipFileWithProgress(esVersion)
    } else {
      logger.info(s"ES $esVersion for Windows already downloaded")
    }
  }

  private def doDownloadEsZipFileWithProgress(esVersion: String): Unit = {
    val url = downloadUrl(esVersion)
    val dest = zipFilePath(esVersion)

    logger.info(s"Downloading ES for Windows from $url to $dest")

    val connection = java.net.URI.create(url).toURL.openConnection()
    val fileSize = connection.getContentLength

    val in = new BufferedInputStream(connection.getInputStream)
    val out = new FileOutputStream(dest.toIO)

    val buffer = new Array[Byte](fileSize)
    var bytesRead = 0L
    var read = 0
    var lastPercent = 0

    logger.info(s"Zip file size: ${fileSize / 1024 / 1024} MB")

    while ( {
      read = in.read(buffer)
      read != -1
    }) {
      out.write(buffer, 0, read)
      bytesRead += read

      if (fileSize > 0) {
        val percent = ((bytesRead * 100.0) / fileSize).toInt
        if ((percent - lastPercent) >= 4) {
          logger.info(s"Download progress: [ ${List.fill(percent / 2)("#").mkString}${List.fill((100 - percent) / 2)(" ").mkString} ] $percent% ")
          lastPercent = percent
        }
      }
    }
    in.close()
    out.close()
  }

  private def unzip(esVersion: String, config: Config): Unit = {
    logger.info(s"Unzipping ES")
    val zipFile = zipFilePath(esVersion)
    val targetDir = esPath(config.clusterName, config.nodeName)
    os.makeDir.all(targetDir)
    val zis = new ZipInputStream(Files.newInputStream(zipFile.toNIO))
    try {
      LazyList
        .continually(zis.getNextEntry)
        .takeWhile(_ != null)
        .foreach { entry =>
          val relPath = os.RelPath(entry.getName)

          // Drop the root folder name (first path segment)
          val strippedPath =
            if (relPath.segments.nonEmpty)
              os.RelPath.fromStringSegments(relPath.segments.tail.toArray)
            else
              relPath

          val newPath = targetDir / strippedPath

          if (entry.isDirectory) os.makeDir.all(newPath)
          else {
            os.makeDir.all(newPath / os.up)
            Files.copy(zis, newPath.toNIO, StandardCopyOption.REPLACE_EXISTING)
          }
        }
    } finally {
      zis.close()
      logger.info(s"Unzipped ES")
    }
  }

  def aaa(config: Config): Unit = {
    val configFile = configFilePath(config.clusterName, config.nodeName)
    val lines = os.read.lines(configFile).toVector

    def setOrAdd(key: String, value: String, lines: Vector[String]): Vector[String] = {
      val keyPrefix = key + ":"
      val updated = lines.map {
        case line if line.trim.startsWith(keyPrefix) => s"$key: $value"
        case line => line
      }
      if (updated.exists(_.trim.startsWith(keyPrefix))) updated
      else updated :+ s"$key: $value"
    }

    val withSecurity = setOrAdd("xpack.security.enabled", "true", lines)
    val withSSL = setOrAdd("xpack.security.transport.ssl.enabled", "true", withSecurity)

    os.write.over(configFile, withSSL.mkString("\n"))
  }

  private def replaceConfigFile(elasticsearch: Elasticsearch): Unit = {
    elasticsearch.esConfigFile
      .copyTo(
        File(configFilePath(elasticsearch.config.clusterName, elasticsearch.config.nodeName).toString),
        overwrite = true
      )
  }

  private def startElasticsearch(config: Config): Unit = {
    val binDir = binPath(config.clusterName, config.nodeName)
    val esBat = binDir / "elasticsearch.bat"
    logger.info(s"Starting Elasticsearch [${config.clusterName}][${config.nodeName}]")

    val proc = os.proc(esBat)
      .spawn(
        cwd = binDir,
        stdout = os.ProcessOutput.Readlines(line => logger.info(s"[ES][${config.clusterName}][${config.nodeName}] $line")),
        stderr = os.ProcessOutput.Readlines(line => logger.error(s"[ES][${config.clusterName}][${config.nodeName}] $line")),
      )

    sys.addShutdownHook {
      logger.info(s"JVM shutting down, stopping Elasticsearch [${config.clusterName}][${config.nodeName}] process...")
      try {
        proc.destroy()
        proc.join()
      } catch {
        case e: Exception => logger.error(s"Failed to stop Elasticsearch [${config.clusterName}][${config.nodeName}] process: ${e.getMessage}")
      }
    }
  }
}
