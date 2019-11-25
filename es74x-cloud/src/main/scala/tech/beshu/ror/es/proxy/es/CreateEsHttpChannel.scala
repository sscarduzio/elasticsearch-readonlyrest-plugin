package tech.beshu.ror.es.proxy.es

import java.net.InetSocketAddress

import org.elasticsearch.action.ActionListener
import org.elasticsearch.http.{HttpChannel, HttpResponse}

object CreateEsHttpChannel {

  val dummyHttpChannel: HttpChannel = new HttpChannel {
    override def sendResponse(response: HttpResponse, listener: ActionListener[Void]): Unit = ()
    override def getLocalAddress: InetSocketAddress = new InetSocketAddress(5000) // todo:
    override def getRemoteAddress: InetSocketAddress = new InetSocketAddress(5000) // todo:
    override def close(): Unit = ()
    override def addCloseListener(listener: ActionListener[Void]): Unit = ()
    override def isOpen: Boolean = false
  }
}
