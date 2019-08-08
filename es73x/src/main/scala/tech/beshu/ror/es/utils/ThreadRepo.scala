package tech.beshu.ror.es.utils

import org.elasticsearch.rest.RestChannel
import tech.beshu.ror.SecurityPermissionException

object ThreadRepo {
  private val threadLocalChannel = new ThreadLocal[RestChannel]

  def setRestChannel(restChannel: RestChannel): Unit = {
    threadLocalChannel.set(restChannel)
  }

  def getRestChannel: Option[RestChannel] = {
    val channel = threadLocalChannel.get
    if (channel != null) threadLocalChannel.remove()
    val reqNull =
      if (channel == null) true
      else channel.request == null
    if (shouldSkipACL(channel == null, reqNull)) None
    else Option(channel)
  }

  private def shouldSkipACL(chanNull: Boolean, reqNull: Boolean): Boolean = { // This was not a REST message
    if (reqNull && chanNull) return true
    // Bailing out in case of catastrophical misconfiguration that would lead to insecurity
    if (reqNull != chanNull) {
      if (chanNull) throw new SecurityPermissionException("Problems analyzing the channel object. " + "Have you checked the security permissions?", null)
      if (reqNull) throw new SecurityPermissionException("Problems analyzing the request object. " + "Have you checked the security permissions?", null)
    }
    false
  }
}
