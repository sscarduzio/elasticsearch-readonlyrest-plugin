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
import org.elasticsearch.common.bytes.BytesReference
import org.elasticsearch.common.io.stream.BytesStreamOutput
import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.rest.{RestChannel, RestRequest, RestResponse}
import org.elasticsearch.tasks.Task

class RorRestChannel(underlying: RestChannel, task: Task)
  extends RestChannelDelegate(underlying)
    with Logging {

    override def sendResponse(response: RestResponse): Unit = {
      TransportServiceInterceptor.taskManagerSupplier.get() match {
        case Some(taskManager) => taskManager.unregister(task)
        case None => logger.warn(s"Cannot unregister task: ${task.getId}; ${task.getDescription}")
      }
      super.sendResponse(response)
    }
  }

sealed class RestChannelDelegate(delegate: RestChannel) extends RestChannel {
  override def newBuilder(): XContentBuilder = delegate.newBuilder()

  override def newBuilder(autoDetectSource: BytesReference, useFiltering: Boolean): XContentBuilder =
    delegate.newBuilder(autoDetectSource, useFiltering)

  override def newErrorBuilder(): XContentBuilder = delegate.newErrorBuilder()

  override def bytesOutput(): BytesStreamOutput = delegate.bytesOutput()

  override def request(): RestRequest = delegate.request()

  override def detailedErrorsEnabled(): Boolean = delegate.detailedErrorsEnabled()

  override def sendResponse(response: RestResponse): Unit = delegate.sendResponse(response)

}