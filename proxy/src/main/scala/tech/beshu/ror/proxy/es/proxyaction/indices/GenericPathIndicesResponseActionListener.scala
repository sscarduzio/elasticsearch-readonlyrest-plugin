//package tech.beshu.ror.proxy.es.proxyaction.indices
//
//import org.elasticsearch.action.ActionListener
//import tech.beshu.ror.proxy.es.ProxyRestChannel
//
//class GenericPathIndicesResponseActionListener(restChannel: ProxyRestChannel)
//  extends ActionListener[GenericPathIndicesResponse] {
//
//  override def onResponse(response: GenericPathIndicesResponse): Unit = {
//    restChannel.sendResponse(response.toRestResponse)
//  }
//
//  override def onFailure(e: Exception): Unit = {
//    restChannel.sendFailureResponse(e)
//  }
//}
