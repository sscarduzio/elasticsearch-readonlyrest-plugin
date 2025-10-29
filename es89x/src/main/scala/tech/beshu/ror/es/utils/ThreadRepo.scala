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

import org.elasticsearch.rest.RestRequest
import tech.beshu.ror.accesscontrol.domain.UriPath
import tech.beshu.ror.es.RorRestChannel

import scala.util.{Failure, Success, Try}

object ThreadRepo {
  private val threadLocalChannel = new ThreadLocal[RorRestChannel]

  def safeSetRestChannel(restChannel: RorRestChannel)(code: => Unit): Unit = {
    threadLocalChannel.set(restChannel)
    Try(code) match {
      case Success(_) =>
      case Failure(ex) => removeRestChannel(restChannel)
    }
  }

  def removeRestChannel(restChannel: RorRestChannel): Unit = {
    if (threadLocalChannel.get() == restChannel) threadLocalChannel.remove()
  }

  def getRorRestChannel: Option[RorRestChannel] = {
    for {
      channel <- Option(threadLocalChannel.get)
      request <- Option(channel.request())
    } yield {
      if(!shouldRemovingRestChannelBePostponedFor(request)) threadLocalChannel.remove()
      channel
    }
  }

  private def shouldRemovingRestChannelBePostponedFor(request: RestRequest) = {
    // because of the new implementation of RestTemplatesAction in ES 7.16.0 which don't take into consideration
    // modification of ActionRequest, this workaround has to be introduced - we have to not remove the Rest Channel
    // from the thread local, so the get composable templates request will be processed by ROR's ACL
    UriPath
      .from(request.uri())
      .exists(_.isCatTemplatePath)
  }
}
