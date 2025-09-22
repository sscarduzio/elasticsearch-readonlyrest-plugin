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

import java.util.concurrent.atomic.AtomicReference

abstract class WindowsPseudoContainer[T <: GenericContainer[T], SERVICE] extends GenericContainer[T]("noop:latest") {

  protected def name: String

  protected def prepare(): SERVICE

  protected def destroy(service: SERVICE): Unit

  protected def getPort(service: SERVICE): Int

  private val service: AtomicReference[Option[SERVICE]] = new AtomicReference(Option.empty[SERVICE])

  override final def start(): Unit = doStart()

  override final def doStart(): Unit = {
    val success = service.compareAndSet(None, Some(prepare()))
    if (!success) throw new IllegalStateException(s"Service $name is already started, cannot start again.")
  }

  override final def stop(): Unit = {
    service.getAndUpdate {
      case Some(service) =>
        destroy(service)
        None
      case None =>
        throw new IllegalStateException(s"Service $name is not started, cannot stop.")
    }
  }

  def getPort: Int = {
    service.get() match {
      case Some(service) => getPort(service)
      case None => throw new IllegalStateException(s"Service $name is not started, port is not yet defined.")
    }
  }

  override final def getContainerId: String = name

  override final def getDockerImageName: String = name

}
