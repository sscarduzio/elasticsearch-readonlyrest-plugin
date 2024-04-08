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

import org.elasticsearch.common.util.concurrent.ThreadContext
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.utils.JavaConverters

import scala.language.implicitConversions

final class ThreadContextOps(val threadContext: ThreadContext) extends AnyVal {

  def stashAndMergeResponseHeaders(esContext: EsContext): ThreadContext.StoredContext = {
    val responseHeaders =
      JavaConverters.flattenPair(threadContext.getResponseHeaders).toSet ++ esContext.threadContextResponseHeaders
    val storedContext = threadContext.stashContext()
    responseHeaders.foreach { case (k, v) => threadContext.addResponseHeader(k, v) }
    storedContext
  }

  def putHeaderIfNotPresent(header: Header): ThreadContext = {
    Option(threadContext.getHeader(header.name.value.value)) match {
      case Some(_) =>
      case None => threadContext.putHeader(header.name.value.value, header.value.value)
    }
    threadContext
  }

  def addRorUserAuthenticationHeader(nodeName: String): ThreadContext = {
    putHeaderIfNotPresent(XPackSecurityAuthenticationHeader.createRorUserAuthenticationHeader(nodeName))
  }

  def addXpackSecurityAuthenticationHeader(nodeName: String): ThreadContext = {
    putHeaderIfNotPresent(XPackSecurityAuthenticationHeader.createXpackSecurityAuthenticationHeader(nodeName))
  }

  def addSystemAuthenticationHeader(nodeName: String): ThreadContext = {
    putHeaderIfNotPresent(XPackSecurityAuthenticationHeader.createSystemAuthenticationHeader(nodeName))
  }
}

object ThreadContextOps {
  implicit def createThreadContextOps(threadContext: ThreadContext): ThreadContextOps = new ThreadContextOps(threadContext)
}
