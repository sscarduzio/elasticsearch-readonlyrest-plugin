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
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.tasks.TaskManager
import org.elasticsearch.transport.TransportService

class TransportServiceInterceptor(settings: Settings,
                                  transportService: TransportService,
                                  ignore: Unit) // hack!
  extends AbstractLifecycleComponent(settings) {

    @Inject
    def this(settings: Settings, transportService: TransportService) {
      this(settings, transportService, ())
    }
    Option(transportService.getTaskManager).foreach { t  =>
      TransportServiceInterceptor.taskManagerSupplier.update(t)
    }
    override def doStart(): Unit = {}
    override def doStop(): Unit = {}
    override def doClose(): Unit = {}
  }
object TransportServiceInterceptor {
  val taskManagerSupplier: TaskManagerSupplier = new TaskManagerSupplier
}

class TaskManagerSupplier extends Supplier[Option[TaskManager]] {
  private val taskManagerAtomicReference = new AtomicReference(Option.empty[TaskManager])
  override def get(): Option[TaskManager] = taskManagerAtomicReference.get() match {
    case Some(value) => Option(value)
    case None => Option.empty
  }
  def update(taskManager: TaskManager): Unit = taskManagerAtomicReference.set(Some(taskManager))
}
