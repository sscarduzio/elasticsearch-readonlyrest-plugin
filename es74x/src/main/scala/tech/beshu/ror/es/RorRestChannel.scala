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

import monix.execution.atomic.Atomic
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.index.reindex.BulkByScrollTask
import org.elasticsearch.rest.{AbstractRestChannel, RestChannel, RestResponse}
import org.elasticsearch.tasks.{CancellableTask, Task}

class RorRestChannel(underlying: RestChannel)
  extends AbstractRestChannel(underlying.request(), true)
    with ResponseFieldsFiltering
    with Logging {

  private val maybeTask: Atomic[Option[Task]] = Atomic(None: Option[Task])

  def setTask(task: Task): Unit = {
    maybeTask.set(Some(task))
  }

  override def sendResponse(response: RestResponse): Unit = {
    unregisterTask()
    underlying.sendResponse(filterRestResponse(response))
  }

  private def unregisterTask(): Unit = {
    maybeTask.get().foreach { task =>
      TransportServiceInterceptor.taskManagerSupplier.get() match {
        case Some(taskManager) =>
          Option(task) match {
            case Some(t: CancellableTask) =>
            // hack:  for some reason we should not unregister this type of task because Kibana 7.12.x are not able
            //        to do its migrations
              taskManager.cancel(t, "test", () => ())
              taskManager.unregister(task)
            case Some(_) | None =>
              taskManager.unregister(task)
          }
        case None => logger.error(s"Cannot unregister task: ${task.getId}; ${task.getDescription}")
      }
    }
  }
}
