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
import org.testcontainers.containers.output.OutputFrame
import os.*
import tech.beshu.ror.utils.containers.images.Elasticsearch.Config
import tech.beshu.ror.utils.containers.windows.WindowsEsDirectoryManager.binPath

import java.util.function.Consumer
import scala.language.postfixOps

object WindowsEsRunner extends LazyLogging {

  def startEs(config: Config,
              additionalLogConsumer: Option[Consumer[OutputFrame]]): WindowsEsProcess = {
    val binDir = binPath(config.clusterName, config.nodeName)
    val esBat = binDir / "elasticsearch.bat"
    logger.info(s"Starting Elasticsearch [${config.clusterName}][${config.nodeName}]")

    val proc = os.proc(esBat)
      .spawn(
        cwd = binDir,
        env = Map(
          "ES_JAVA_OPTS" -> "-Xms400m -Xmx400m",
          "JAVA_HOME" -> (binDir / ".." / "jdk").toString
        ) ++ config.envs,
        stdout = os.ProcessOutput.Readlines { line =>
          logger.info(s"[${config.nodeName}] $line")
          additionalLogConsumer.foreach(_.accept(new OutputFrame(OutputFrame.OutputType.STDOUT, line.getBytes)))
        },
        stderr = os.ProcessOutput.Readlines { line =>
          logger.error(s"[${config.nodeName}] $line")
          additionalLogConsumer.foreach(_.accept(new OutputFrame(OutputFrame.OutputType.STDERR, line.getBytes)))
        },
      )
    val process = WindowsEsProcess(config.clusterName, config.nodeName, proc)

    sys.addShutdownHook {
      logger.info(s"JVM shutting down, stopping Elasticsearch [${config.clusterName}][${config.nodeName}] process...")
      process.kill()
    }

    process
  }

  class WindowsEsProcess(val clusterName: String, val nodeName: String, proc: SubProcess) {
    def kill(): Unit = {
      logger.info(s"Stopping ES process with pid ${proc.wrapped.pid}")
      try {
        os.proc("taskkill", "/PID", proc.wrapped.pid.toString, "/F", "/T").call()
      } catch {
        case e: Exception => logger.error(s"Failed to stop Elasticsearch [${clusterName}][${nodeName}] process: ${e.getMessage}")
      }
    }
  }

}
