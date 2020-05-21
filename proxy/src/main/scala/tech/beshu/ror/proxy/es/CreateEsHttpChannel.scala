/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import java.net.InetSocketAddress

import org.elasticsearch.action.ActionListener
import org.elasticsearch.http.{HttpChannel, HttpResponse}

object CreateEsHttpChannel {

  def dummyHttpChannel(proxyPort: Int): HttpChannel = new HttpChannel {
    override def sendResponse(response: HttpResponse, listener: ActionListener[Void]): Unit = ()
    override def getLocalAddress: InetSocketAddress = new InetSocketAddress(proxyPort)
    override def getRemoteAddress: InetSocketAddress = new InetSocketAddress(proxyPort)
    override def close(): Unit = ()
    override def addCloseListener(listener: ActionListener[Void]): Unit = ()
    override def isOpen: Boolean = false
  }
}
