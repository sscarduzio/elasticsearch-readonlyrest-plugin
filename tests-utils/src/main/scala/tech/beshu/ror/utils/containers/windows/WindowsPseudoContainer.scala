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
import tech.beshu.ror.utils.containers.windows.WindowsPseudoContainer.Service

import java.util.concurrent.atomic.AtomicReference

abstract class WindowsPseudoContainer[T <: GenericContainer[T]] extends GenericContainer[T]("noop:latest") {

  protected def name: String

  protected def prepare(): Service

  protected def awaitReady(): Unit

  private val service: AtomicReference[Option[Service]] = new AtomicReference(Option.empty[Service])

  override final def start(): Unit = doStart()

  override final def doStart(): Unit = synchronized {
    service.get() match {
      case Some(_) =>
        throw new IllegalStateException(s"Service $name is already started, cannot start again.")
      case None =>
        service.set(Some(prepare()))
        awaitReady()
    }
  }

  override final def stop(): Unit = synchronized {
    service.get() match {
      case Some(startedService) =>
        startedService.destroy()
        service.set(None)
      case None =>
        throw new IllegalStateException(s"Service $name is not started, cannot stop.")
    }
  }

  def getPort: Int = {
    service.get() match {
      case Some(startedService) => startedService.getPort
      case None => throw new IllegalStateException(s"Service $name is not started, port is not yet defined.")
    }
  }

  override final def getContainerId: String = name

  override final def getDockerImageName: String = name

}

object WindowsPseudoContainer {
  trait Service {
    def destroy(): Unit

    def getPort: Int
  }
}
