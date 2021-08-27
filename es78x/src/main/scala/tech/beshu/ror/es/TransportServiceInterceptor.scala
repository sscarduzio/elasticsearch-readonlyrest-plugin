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
package tech.beshu.ror.es

import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

import org.elasticsearch.common.component.AbstractLifecycleComponent
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.transport.{RemoteClusterService, TransportService}

class TransportServiceInterceptor(transportService: TransportService,
                                  ignore: Unit) // hack!
  extends AbstractLifecycleComponent() {

  @Inject
  def this(transportService: TransportService) {
    this(transportService, ())
  }
  Option(transportService.getRemoteClusterService).foreach { r =>
    TransportServiceInterceptor.remoteClusterServiceSupplier.update(r)
  }
  override def doStart(): Unit = {}
  override def doStop(): Unit = {}
  override def doClose(): Unit = {}
}
object TransportServiceInterceptor {
  val remoteClusterServiceSupplier: RemoteClusterServiceSupplier = new RemoteClusterServiceSupplier
}

class RemoteClusterServiceSupplier extends Supplier[Option[RemoteClusterService]] {
  private val remoteClusterServiceAtomicReference = new AtomicReference(Option.empty[RemoteClusterService])
  override def get(): Option[RemoteClusterService] = remoteClusterServiceAtomicReference.get() match {
    case Some(value) => Option(value)
    case None => Option.empty
  }
  def update(service: RemoteClusterService): Unit = remoteClusterServiceAtomicReference.set(Some(service))
}