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
import os.SubProcess
import tech.beshu.ror.utils.containers.ElasticsearchNodeWaitingStrategy
import tech.beshu.ror.utils.containers.images.Elasticsearch
import tech.beshu.ror.utils.containers.windows.WindowsElasticsearchSetup.prepareAndStartEsForWindows

class WindowsBasedEsPseudoGenericContainer(elasticsearch: Elasticsearch, waitStrategy: ElasticsearchNodeWaitingStrategy)
  extends GenericContainer[WindowsBasedEsPseudoGenericContainer]("noop:latest") {

  private var processAndPort: Option[(SubProcess, Int)] = None
    
  override def start(): Unit = {
    doStart()
  }

  override def doStart(): Unit = {
    processAndPort match {
      case Some(_) =>
        println("Trying to start already started ES")
        ()
      case None =>
        processAndPort = Some(prepareAndStartEsForWindows(elasticsearch))
        waitStrategy.waitUntilReady()
    }
  }

  override def stop(): Unit = {
    super.stop()
    processAndPort.foreach { case (proc, _) =>
      println(s"ZZZZZZZZZZ stop $processAndPort")
      os.proc("taskkill", "/PID", proc.wrapped.pid.toString, "/F", "/T").call()
    }
    processAndPort = None
  }
  
  def getPort: Int = processAndPort.map(_._2).getOrElse(throw new IllegalStateException("The ES is not started, port is not yet defined"))

  override def getContainerId: String = "WindowsBasedEsPseudoGenericContainer"

}
