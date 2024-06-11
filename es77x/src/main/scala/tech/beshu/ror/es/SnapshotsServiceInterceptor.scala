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
import org.elasticsearch.snapshots.SnapshotsService

import scala.annotation.unused

class SnapshotsServiceInterceptor(snapshotsService: SnapshotsService,
                                  @unused constructorDiscriminator: Unit)
  extends AbstractLifecycleComponent() {

  @Inject
  def this(snapshotsService: SnapshotsService) = {
    this(snapshotsService, ())
  }

  SnapshotsServiceInterceptor.snapshotsServiceSupplier.update(snapshotsService)

  override def doStart(): Unit = {}
  override def doStop(): Unit = {}
  override def doClose(): Unit = {}
}
object SnapshotsServiceInterceptor {
  val snapshotsServiceSupplier: SnapshotsServiceSupplier = new SnapshotsServiceSupplier
}

class SnapshotsServiceSupplier extends Supplier[Option[SnapshotsService]] {
  private val snapshotsServiceAtomicReference = new AtomicReference(Option.empty[SnapshotsService])
  override def get(): Option[SnapshotsService] = snapshotsServiceAtomicReference.get() match {
    case Some(value) => Option(value)
    case None => Option.empty
  }
  def update(service: SnapshotsService): Unit = snapshotsServiceAtomicReference.set(Some(service))
}
