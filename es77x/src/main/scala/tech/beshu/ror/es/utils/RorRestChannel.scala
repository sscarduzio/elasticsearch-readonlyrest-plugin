package tech.beshu.ror.es.utils

import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.common.io.stream.BytesStreamOutput
import org.elasticsearch.common.xcontent.{XContentBuilder, XContentType}
import org.elasticsearch.rest.{RestChannel, RestRequest, RestResponse}
import org.elasticsearch.tasks.Task
import tech.beshu.ror.es.TransportServiceInterceptor

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

  override def newErrorBuilder(): XContentBuilder = delegate.newErrorBuilder()

  override def newBuilder(xContentType: XContentType, useFiltering: Boolean): XContentBuilder =
    delegate.newBuilder(xContentType, useFiltering)

  override def newBuilder(xContentType: XContentType, responseContentType: XContentType, useFiltering: Boolean): XContentBuilder =
    delegate.newBuilder(xContentType, responseContentType, useFiltering)

  override def bytesOutput(): BytesStreamOutput = delegate.bytesOutput()

  override def request(): RestRequest = delegate.request()

  override def detailedErrorsEnabled(): Boolean = delegate.detailedErrorsEnabled()

  override def sendResponse(response: RestResponse): Unit = delegate.sendResponse(response)
}