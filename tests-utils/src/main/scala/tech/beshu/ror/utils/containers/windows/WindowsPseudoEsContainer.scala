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

import org.testcontainers.containers.output.OutputFrame
import tech.beshu.ror.utils.containers.ElasticsearchNodeWaitingStrategy
import tech.beshu.ror.utils.containers.images.Elasticsearch
import tech.beshu.ror.utils.containers.windows.WindowsEsRunner.WindowsEsProcess
import tech.beshu.ror.utils.containers.windows.WindowsEsSetup.prepareAndStartEs

import java.util.function.Consumer

class WindowsPseudoEsContainer(elasticsearch: Elasticsearch,
                               waitStrategy: ElasticsearchNodeWaitingStrategy,
                               additionalLogConsumer: Option[Consumer[OutputFrame]])
  extends WindowsPseudoContainer[WindowsPseudoEsContainer] {

  override protected def name: String = "WindowsPseudoEsContainer"

  override protected def prepare(): WindowsEsProcess = {
    prepareAndStartEs(elasticsearch, additionalLogConsumer)
  }

  override protected def awaitReady(): Unit = {
    waitStrategy.waitUntilReady()
  }

}
