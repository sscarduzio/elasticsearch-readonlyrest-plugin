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

import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.rest.{AbstractRestChannel, RestChannel, RestResponse}
import org.elasticsearch.tasks.Task

class RorRestChannel(underlying: RestChannel, task: Task)
  extends AbstractRestChannel(underlying.request(), false)
    with Logging {

  override def sendResponse(response: RestResponse): Unit = {
    TransportServiceInterceptor.taskManagerSupplier.get() match {
      case Some(taskManager) => taskManager.unregister(task)
      case None => logger.error(s"Cannot unregister task: ${task.getId}; ${task.getDescription}")
    }
    underlying.sendResponse(response)
  }
}
