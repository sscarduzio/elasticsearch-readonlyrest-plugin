/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import org.elasticsearch.tasks.Task

object ProxyThreadRepo {
  private val threadLocalChannel = new ThreadLocal[ProxyRestChannel]

  def setRestChannel(restChannel: ProxyRestChannel): Unit = {
    threadLocalChannel.set(restChannel)
  }

  def getRestChannel(task: Task): Option[ProxyRestChannel] = {
    val result = Option(threadLocalChannel.get).map(new UnregisteringTaskProxyRestChannel(_, task))
    threadLocalChannel.remove()
    result
  }
  
  def clearRestChannel(): Unit = {
    threadLocalChannel.remove()
  }
}
