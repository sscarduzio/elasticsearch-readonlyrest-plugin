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

import org.elasticsearch.Version
import org.elasticsearch.common.bytes.BytesReference
import org.elasticsearch.common.io.stream.BytesStreamOutput
import org.elasticsearch.common.util.concurrent.ThreadContext
import org.elasticsearch.transport.{Transport, TransportInterceptor, TransportRequest, TransportRequestOptions, TransportResponse, TransportResponseHandler}

import java.util.Base64
import scala.collection.JavaConverters._

class RorTransportInterceptor(threadContext: ThreadContext, nodeName: String) extends TransportInterceptor {

  import RorTransportInterceptor.AuthenticationKey

  override def interceptSender(sender: TransportInterceptor.AsyncSender): TransportInterceptor.AsyncSender = new TransportInterceptor.AsyncSender {
    override def sendRequest[T <: TransportResponse](connection: Transport.Connection,
                                                     action: String,
                                                     request: TransportRequest,
                                                     options: TransportRequestOptions,
                                                     handler: TransportResponseHandler[T]): Unit = {
      if (threadContext.getHeader(AuthenticationKey) == null) {
        threadContext.putHeader(AuthenticationKey, getAuthenticationHeaderValue)
      }
      sender.sendRequest(connection, action, request, options, handler)
    }
  }

  private def getAuthenticationHeaderValue: String = {
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

object RorTransportInterceptor {
  private val AuthenticationKey = "_xpack_security_authentication"
}