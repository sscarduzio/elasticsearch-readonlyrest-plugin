/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.genericaction

import org.elasticsearch.action.ActionListener
import tech.beshu.ror.proxy.es.ProxyRestChannel

class GenericResponseActionListener(restChannel: ProxyRestChannel)
  extends ActionListener[GenericResponse] {

  override def onResponse(response: GenericResponse): Unit = {
    restChannel.sendResponse(response.toRestResponse)
  }

  override def onFailure(e: Exception): Unit = {
    restChannel.sendFailureResponse(e)
  }
}
