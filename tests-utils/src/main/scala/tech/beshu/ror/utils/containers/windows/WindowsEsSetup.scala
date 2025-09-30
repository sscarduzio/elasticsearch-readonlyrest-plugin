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
import tech.beshu.ror.utils.containers.images.Elasticsearch
import tech.beshu.ror.utils.containers.images.Elasticsearch.Plugin
import tech.beshu.ror.utils.containers.images.Elasticsearch.Plugin.PluginInstallationStep
import tech.beshu.ror.utils.containers.windows.WindowsEsDirectoryManager.*
import tech.beshu.ror.utils.containers.windows.WindowsEsPortProvider.*
import tech.beshu.ror.utils.containers.windows.WindowsEsRunner.{WindowsEsProcess, startEs}

import java.util.function.Consumer
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
    installPlugins(elasticsearch)
  }

  private def installPlugins(elasticsearch: Elasticsearch): Unit = {
    val plugins = elasticsearch.plugins
    val numberOfPlugins = plugins.size
    plugins.zipWithIndex.foreach(installPlugin(elasticsearch, numberOfPlugins))
  }

  private def installPlugin(elasticsearch: Elasticsearch, numberOfPlugins: Int)
                           (plugin: Plugin, index: Int): Unit = {
    logger.info(s"Installing plugin ${index + 1}/$numberOfPlugins")
    val steps = plugin.installationSteps(elasticsearch.config).steps
    val numberOfSteps = steps.size
    steps.zipWithIndex.foreach(runInstallationStep(elasticsearch, numberOfSteps))
  }

  private def runInstallationStep(elasticsearch: Elasticsearch, numberOfSteps: Int)
                                 (step: PluginInstallationStep, index: Int): Unit = step match {
    case PluginInstallationStep.CopyFile(destination, file) =>
      logger.info(s"Step ${index + 1}/$numberOfSteps: copy file $destination $file")
      os.makeDir.all(destination / os.up)
      val destBetterFile = File(destination.toNIO)
      file.copyTo(destBetterFile, overwrite = true)
    case PluginInstallationStep.RunCommand(_, windowsCommand) =>
      logger.info(s"Step ${index + 1}/$numberOfSteps: run command $windowsCommand")
      os.proc("cmd", "/c", windowsCommand).call(
        cwd = esPath(elasticsearch.config.clusterName, elasticsearch.config.nodeName),
        stdout = os.ProcessOutput.Readlines(line => logger.info(s"[Step ${index + 1}/$numberOfSteps cmd] $line")),
        stderr = os.ProcessOutput.Readlines(line => logger.error(s"[Step ${index + 1}/$numberOfSteps cmd] $line")),
      )
    case PluginInstallationStep.ChangeUser(_) =>
      logger.info(s"Step ${index + 1}/$numberOfSteps: change user is ignored on Windows")
  }

  private def replaceConfigFile(elasticsearch: Elasticsearch): Unit = {
    // Add port configuration at the top of the config file
    val esPorts = WindowsEsPortProvider.get(elasticsearch.config.nodeName)
    val file =
      elasticsearch
        .esConfigFile
        .appendLine(s"http.port: ${esPorts.esPort}")
        .appendLine(s"transport.port: ${esPorts.transportPort}")
        .replaceLineWithPrefix("network.host:", "network.host: 127.0.0.1")

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

  extension (file: File)
    private def replaceLineWithPrefix(prefix: String, newLine: String): File =
      val updated = file.lines.map(line => if (line.startsWith(prefix)) newLine else line)
      file.overwrite(updated.mkString("\n"))

}
