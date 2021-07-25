package tech.beshu.ror.es

import org.elasticsearch.Version
import org.elasticsearch.common.bytes.BytesReference
import org.elasticsearch.common.io.stream.BytesStreamOutput
import org.elasticsearch.common.util.concurrent.ThreadContext
import org.elasticsearch.transport.{Transport, TransportInterceptor, TransportRequest, TransportRequestOptions, TransportResponse, TransportResponseHandler}

import java.util.Base64
import scala.collection.JavaConverters._

class RorTransportInterceptor(threadContext: ThreadContext, nodeName: String) extends TransportInterceptor {

  val AUTHENTICATION_KEY = "_xpack_security_authentication"

  override def interceptSender(sender: TransportInterceptor.AsyncSender): TransportInterceptor.AsyncSender = new TransportInterceptor.AsyncSender {
    override def sendRequest[T <: TransportResponse](connection: Transport.Connection,
                                                     action: String,
                                                     request: TransportRequest,
                                                     options: TransportRequestOptions,
                                                     handler: TransportResponseHandler[T]): Unit = {
      if (threadContext.getHeader(AUTHENTICATION_KEY) == null) {
        threadContext.putHeader(AUTHENTICATION_KEY, getAuthenticationHeaderValue())
      }
      sender.sendRequest(connection, action, request, options, handler)
    }
  }

  private def getAuthenticationHeaderValue() = {
    val output = new BytesStreamOutput()
    val currentVersion = Version.CURRENT
    output.setVersion(currentVersion)
    Version.writeVersion(currentVersion, output)
    output.writeBoolean(true)
    output.writeString("_system")
    output.writeString(nodeName)
    output.writeString("__attach")
    output.writeString("__attach")
    output.writeBoolean(false)
    if (output.getVersion.onOrAfter(Version.V_6_7_0)) {
      output.writeVInt(4) // Internal
      output.writeMap(Map[String, Object]().asJava)
    }
    Base64.getEncoder().encodeToString(BytesReference.toBytes(output.bytes()));
  }
}
