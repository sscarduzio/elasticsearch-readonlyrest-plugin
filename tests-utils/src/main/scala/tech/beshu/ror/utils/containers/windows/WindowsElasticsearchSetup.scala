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
import tech.beshu.ror.utils.containers.images.Elasticsearch.Plugin.EsUpdateStep

import java.io.{BufferedInputStream, FileOutputStream}
import java.nio.file.{Files, StandardCopyOption}
import java.util.zip.ZipInputStream
import scala.language.postfixOps

object WindowsElasticsearchSetup extends LazyLogging {

  def downloadUrl(esVersion: String): String = {
    if (esVersion.startsWith("6")) {
      s"https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-$esVersion.zip"
    } else {
      s"https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-$esVersion-windows-x86_64.zip"
    }
  }

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

  val ports: Map[String, (Int, Int)] = Map(
    "ROR_SINGLE_1" -> (9200, 9300),
    "ROR1_1" -> (9201, 9301),
    "ROR1_2" -> (9202, 9302),
    "AUDIT_1" -> (9203, 9303),
    "AUDIT_2" -> (9204, 9304),
    "testEsCluster_1" -> (9205, 9305),
    "testEsCluster_2" -> (9206, 9306),
    "ROR2_1" -> (9207, 9307),
    "ROR2_2" -> (9208, 9308),
    "ROR_L1_1" -> (9209, 9309),
    "ROR_L1_2" -> (9210, 9310),
    "XPACK_1" -> (9211, 9311),
    "XPACK_2" -> (9212, 9312),
    "fips_cluster_1" -> (9213, 9313),
    "fips_cluster_2" -> (9214, 9314),
    "ROR_SOURCE_ES_1" -> (9215, 9315),
    "ROR_SOURCE_ES_2" -> (9216, 9316),
    "ROR_DEST_ES_1" -> (9217, 9317),
    "ROR_DEST_ES_2" -> (9218, 9318),
    "ror_xpack_cluster_1" -> (9219, 9319),
    "ror_xpack_cluster_2" -> (9220, 9320),
    "ROR_R1_1" -> (9221, 9321),
    "ROR_R1_2" -> (9222, 9322),
    "ROR_R2_1" -> (9223, 9323),
    "ROR_R2_2" -> (9224, 9324),
    "ROR_1_1" -> (9225, 9325),
    "ROR_1_2" -> (9226, 9326),
    "ROR_2_1" -> (9227, 9327),
    "ROR_2_2" -> (9228, 9328),
    "ROR_3_1" -> (9229, 9329),
    "ROR_3_2" -> (9230, 9330),
    "ror_xpack_cluster_3" -> (9231, 9331),
  )

  def prepareAndStartEsForWindows(elasticsearch: Elasticsearch): (SubProcess, Int) = {
    val (esPort, transportPort) = ports.getOrElse(
      elasticsearch.config.nodeName,
      throw new IllegalStateException(s"No predefined ports for node ${elasticsearch.config.nodeName}")
    )
    downloadEsZipFileWithProgress(elasticsearch.esVersion)
    unzip(elasticsearch.esVersion, elasticsearch.config)
    replaceConfigFile(elasticsearch, esPort, transportPort)
    elasticsearch.esUpdateSteps.steps.zipWithIndex.foreach(runStep(elasticsearch, elasticsearch.esUpdateSteps.steps.size))
    (startElasticsearch(elasticsearch.config), esPort)
  }

  private def runStep(elasticsearch: Elasticsearch, numberOfSteps: Int)(step: EsUpdateStep, index: Int): Unit = step match {
    case EsUpdateStep.CopyFile(destination, file) =>
      logger.info(s"Step ${index + 1}/$numberOfSteps: copy file $destination $file")
      os.makeDir.all(destination / os.up)
      val destBetterFile = File(destination.toNIO)
      file.copyTo(destBetterFile, overwrite = true)
      logger.info(s"Step ${index + 1}/$numberOfSteps: done")
    case EsUpdateStep.RunCommand(_, windowsCommand) =>
      logger.info(s"Step ${index + 1}/$numberOfSteps: run command $windowsCommand")
      val cmd = os.proc("cmd", "/c", windowsCommand)
      val result = cmd.call(
        cwd = esPath(elasticsearch.config.clusterName, elasticsearch.config.nodeName),
        stdout = os.ProcessOutput.Readlines(line => logger.info(s"[command] $line")),
        stderr = os.ProcessOutput.Readlines(line => logger.error(s"[command] $line")),
      )
      logger.info(s"Step ${index + 1}/$numberOfSteps: done with result $result")
    case EsUpdateStep.ChangeUser(user) =>
      logger.info(s"Step ${index + 1}/$numberOfSteps: change user is ignored on Windows")
  }

  private def downloadEsZipFileWithProgress(esVersion: String): Unit = {
    os.makeDir.all(downloadsPath)
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
    os.remove.all(targetDir)
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

  private def replaceConfigFile(elasticsearch: Elasticsearch, esPort: Int, transportPort: Int): Unit = {
    val file =
      elasticsearch
        .esConfigFile
        .appendLine(s"http.port: $esPort")
        .appendLine(s"transport.port: $transportPort")


    val updatedContent =
      ports.foldLeft(file.contentAsString) {
        case (content, (oldValue, (_, transportPort))) =>
          content.replace(oldValue, s"localhost:$transportPort")
      }

    file.overwrite(updatedContent)
      .copyTo(
        File(configFilePath(elasticsearch.config.clusterName, elasticsearch.config.nodeName).toString),
        overwrite = true
      )
  }

  private def startElasticsearch(config: Config): SubProcess = {
    val binDir = binPath(config.clusterName, config.nodeName)
    val esBat = binDir / "elasticsearch.bat"
    logger.info(s"Starting Elasticsearch [${config.clusterName}][${config.nodeName}]")

    val proc = os.proc(esBat)
      .spawn(
        cwd = binDir,
        env = Map("ES_JAVA_OPTS" -> "-Xms400m -Xmx400m"),
        stdout = os.ProcessOutput.Readlines(line => logger.info(s"[ES][${config.clusterName}][${config.nodeName}] $line")),
        stderr = os.ProcessOutput.Readlines(line => logger.error(s"[ES][${config.clusterName}][${config.nodeName}] $line")),
      )

    sys.addShutdownHook {
      logger.info(s"JVM shutting down, stopping Elasticsearch [${config.clusterName}][${config.nodeName}] process...")
      killEsProcess(config, proc)
    }
    proc
  }

  def killEsProcess(config: Config, proc: SubProcess): Unit = {
    try {
      os.proc("taskkill", "/PID", proc.wrapped.pid.toString, "/F", "/T").call()
    } catch {
      case e: Exception => logger.error(s"Failed to stop Elasticsearch [${config.clusterName}][${config.nodeName}] process: ${e.getMessage}")
    }
  }
}
