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

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.OutputFrame
import tech.beshu.ror.utils.containers.ElasticsearchNodeWaitingStrategy
import tech.beshu.ror.utils.containers.images.Elasticsearch
import tech.beshu.ror.utils.containers.windows.WindowsEsRunner.{WindowsEsProcess, killEs}
import tech.beshu.ror.utils.containers.windows.WindowsEsSetup.prepareAndStartEs

import java.util.function.Consumer

class WindowsPseudoEsContainer(elasticsearch: Elasticsearch,
                               waitStrategy: ElasticsearchNodeWaitingStrategy,
                               additionalLogConsumer: Option[Consumer[OutputFrame]])
  extends GenericContainer[WindowsPseudoEsContainer]("noop:latest") {

  private var windowsEsProcess: Option[WindowsEsProcess] = None

  override def start(): Unit = doStart()

  override def doStart(): Unit = {
    windowsEsProcess match {
      case Some(_) =>
        ()
      case None =>
        windowsEsProcess = Some(prepareAndStartEs(elasticsearch, additionalLogConsumer))
        waitStrategy.waitUntilReady()
    }
  }

  override def stop(): Unit = {
    super.stop()
    windowsEsProcess.foreach(killEs)
  }

  def getPort: Int = windowsEsProcess match {
    case Some(windowsEsProcess) => WindowsEsPortProvider.get(windowsEsProcess.nodeName).esPort
    case None => throw new IllegalStateException("The ES is not started, port is not yet defined")
  }

  override def getContainerId: String = "WindowsPseudoGenericContainerEs"

  override def getDockerImageName: String = "WindowsPseudoGenericContainerEs"
}
