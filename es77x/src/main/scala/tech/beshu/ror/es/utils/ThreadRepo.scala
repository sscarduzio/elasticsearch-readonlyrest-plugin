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
package tech.beshu.ror.es.utils

import org.elasticsearch.rest.RestChannel
import org.elasticsearch.tasks.Task
import tech.beshu.ror.es.RorRestChannel
import tech.beshu.ror.exceptions.SecurityPermissionException

object ThreadRepo {
  private val threadLocalChannel = new ThreadLocal[RestChannel]

  def setRestChannel(restChannel: RestChannel): Unit = {
    threadLocalChannel.set(restChannel)
  }

  def getRorRestChannelFor(task: Task): Option[RorRestChannel] = {
    val channel = threadLocalChannel.get
    if (channel != null) threadLocalChannel.remove()
    val reqNull =
      if (channel == null) true
      else channel.request == null
    if (shouldSkipACL(channel == null, reqNull)) None
    else Option(new RorRestChannel(channel, task))
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
