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
import tech.beshu.ror.boot.SchedulerContextRestore
import tech.beshu.ror.utils.JavaConverters

object ThreadContextOps {

  private val nonAuthTransientNames = List("_authz_info", "_indices_permissions")
  private val transientHeaderNames = "_xpack_security_authentication" :: nonAuthTransientNames

  extension (threadContext: ThreadContext) {

    def setupContextPropagation(): Unit = {
      val capturedTransients = currentTransients(transientHeaderNames)
      val capturedResponseHeaders = currentResponseHeaders
      SchedulerContextRestore.onContextSwitch := Some(() => {
        stashAndRestore(capturedTransients, capturedResponseHeaders)
      })
    }

    def addXpackUserAuthenticationHeader(nodeName: String): ThreadContext = {
      replaceAuthenticationHeader(XPackSecurityAuthenticationHeader.createXpackUserAuthenticationHeader(nodeName))
    }

    def addSystemAuthenticationHeader(nodeName: String): ThreadContext = {
      replaceAuthenticationHeader(XPackSecurityAuthenticationHeader.createSystemAuthenticationHeader(nodeName))
    }

    private def replaceAuthenticationHeader(header: Header): ThreadContext = {
      val headerName = header.name.value.value
      val capturedRequestHeaders = currentRequestHeadersExcluding(headerName)
      stashAndRestore(currentTransients(nonAuthTransientNames), currentResponseHeaders, capturedRequestHeaders)
      threadContext.putHeader(headerName, header.value.value)
      threadContext
    }

    private def currentTransients(names: List[String]): Iterable[(String, AnyRef)] = {
      names.flatMap(n => Option(threadContext.getTransient[AnyRef](n)).map(n -> _))
    }

    private def currentResponseHeaders: Iterable[(String, String)] = {
      JavaConverters.flattenPair(threadContext.getResponseHeaders).toSet
    }

    private def currentRequestHeadersExcluding(excludedName: String): Iterable[(String, String)] = {
      import scala.jdk.CollectionConverters.*
      threadContext.getHeaders.asScala.view.filterNot(_._1 == excludedName)
    }

    private def stashAndRestore(transients: Iterable[(String, AnyRef)],
                                responseHeaders: Iterable[(String, String)],
                                requestHeaders: Iterable[(String, String)] = Iterable.empty): Unit = {
      threadContext.stashContext() // clear thread context (StoredContext intentionally discarded)
      transients.foreach { case (k, v) => threadContext.putTransient(k, v) }
      responseHeaders.foreach { case (k, v) => threadContext.addResponseHeader(k, v) }
      requestHeaders.foreach { case (k, v) => threadContext.putHeader(k, v) }
    }
  }
}
