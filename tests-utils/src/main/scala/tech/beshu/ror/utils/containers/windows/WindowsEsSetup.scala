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
import org.testcontainers.containers.output.OutputFrame
import os.*
import tech.beshu.ror.utils.containers.images.Elasticsearch
import tech.beshu.ror.utils.containers.images.Elasticsearch.Plugin.EsUpdateStep
import tech.beshu.ror.utils.containers.windows.WindowsEsDirectoryManager.*
import tech.beshu.ror.utils.containers.windows.WindowsEsPortProvider.*
import tech.beshu.ror.utils.containers.windows.WindowsEsRunner.{WindowsEsProcess, startEs}

import java.util.function.Consumer
import scala.jdk.CollectionConverters.*
import scala.language.postfixOps

object WindowsEsSetup extends LazyLogging {

  def prepareAndStartEs(elasticsearch: Elasticsearch,
                        additionalLogConsumer: Option[Consumer[OutputFrame]]): WindowsEsProcess = {
    prepareEs(elasticsearch)
    startEs(elasticsearch.config, additionalLogConsumer)
  }

  def prepareEs(elasticsearch: Elasticsearch): Unit = {
    downloadEsZipFileWithProgress(elasticsearch.esVersion)
    unzipEs(elasticsearch.esVersion, elasticsearch.config)
    replaceConfigFile(elasticsearch)
    runUpdateSteps(elasticsearch)
  }

  private def runUpdateSteps(elasticsearch: Elasticsearch): Unit = {
    val numberOfSteps = elasticsearch.esUpdateSteps.steps.size
    elasticsearch.esUpdateSteps.steps.zipWithIndex.foreach(runUpdateStep(elasticsearch, numberOfSteps))
  }

  private def runUpdateStep(elasticsearch: Elasticsearch, numberOfSteps: Int)
                           (step: EsUpdateStep, index: Int): Unit = step match {
    case EsUpdateStep.CopyFile(destination, file) =>
      logger.info(s"Step ${index + 1}/$numberOfSteps: copy file $destination $file")
      os.makeDir.all(destination / os.up)
      val destBetterFile = File(destination.toNIO)
      file.copyTo(destBetterFile, overwrite = true)
    case EsUpdateStep.RunCommand(_, windowsCommand) =>
      logger.info(s"Step ${index + 1}/$numberOfSteps: run command $windowsCommand")
      os.proc("cmd", "/c", windowsCommand).call(
        cwd = esPath(elasticsearch.config.clusterName, elasticsearch.config.nodeName),
        stdout = os.ProcessOutput.Readlines(line => logger.info(s"[Step ${index + 1}/$numberOfSteps cmd] $line")),
        stderr = os.ProcessOutput.Readlines(line => logger.error(s"[Step ${index + 1}/$numberOfSteps cmd] $line")),
      )
    case EsUpdateStep.ChangeUser(_) =>
      logger.info(s"Step ${index + 1}/$numberOfSteps: change user is ignored on Windows")
  }

  private def replaceConfigFile(elasticsearch: Elasticsearch): Unit = {
    // Add port configuration at the top of the config file
    val esPorts = WindowsEsPortProvider.get(elasticsearch.config.nodeName)
    val file =
      elasticsearch
        .esConfigFile(networkHost = "127.0.0.1")
        .appendLine(s"http.port: ${esPorts.esPort}")
        .appendLine(s"transport.port: ${esPorts.transportPort}")

    // Replace all names of other ES hosts with 127.0.0.1 address with correct transport port
    val lines = file.contentAsString.linesIterator.toList
    val updatedContent = lines.head + "\n" +
      ports.foldLeft(lines.tail.mkString("\n")) {
        case (content, (oldValue, WindowsEsPorts(_, transportPort))) =>
          content.replace(oldValue, s"127.0.0.1:$transportPort")
      }

    file.overwrite(updatedContent)
      .copyTo(
        File(configFilePath(elasticsearch.config.clusterName, elasticsearch.config.nodeName).toString),
        overwrite = true
      )
  }
}
