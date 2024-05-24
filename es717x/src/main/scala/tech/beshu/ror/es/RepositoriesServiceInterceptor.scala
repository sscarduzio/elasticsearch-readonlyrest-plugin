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
import org.elasticsearch.repositories.RepositoriesService

import scala.annotation.unused

class RepositoriesServiceInterceptor(repositoriesService: RepositoriesService,
                                     @unused constructorDiscriminator: Unit)
  extends AbstractLifecycleComponent() {

  @Inject
  def this(repositoriesService: RepositoriesService) = {
    this(repositoriesService, ())
  }

  RepositoriesServiceInterceptor.repositoriesServiceSupplier.update(repositoriesService)

  override def doStart(): Unit = {}
  override def doStop(): Unit = {}
  override def doClose(): Unit = {}
}
object RepositoriesServiceInterceptor {
  val repositoriesServiceSupplier: RepositoriesServiceSupplier = new RepositoriesServiceSupplier
}

class RepositoriesServiceSupplier extends Supplier[Option[RepositoriesService]] {
  private val repositoriesServiceAtomicReference = new AtomicReference(Option.empty[RepositoriesService])
  override def get(): Option[RepositoriesService] = repositoriesServiceAtomicReference.get() match {
    case Some(value) => Option(value)
    case None => Option.empty
  }
  def update(service: RepositoriesService): Unit = repositoriesServiceAtomicReference.set(Some(service))
}
