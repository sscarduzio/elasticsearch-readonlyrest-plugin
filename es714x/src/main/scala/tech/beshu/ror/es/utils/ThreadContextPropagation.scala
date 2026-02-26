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
import tech.beshu.ror.boot.SchedulerContextRestore
import tech.beshu.ror.utils.JavaConverters

object ThreadContextPropagation {
  private val transientHeaderNames = List("_xpack_security_authentication", "_authz_info", "_indices_permissions")

  def capture(tc: ThreadContext): Unit = {
    val capturedTransients = transientHeaderNames.flatMap(n => Option(tc.getTransient[AnyRef](n)).map(n -> _)).toMap
    val capturedResponseHeaders = JavaConverters.flattenPair(tc.getResponseHeaders)
    SchedulerContextRestore.onContextSwitch := Some(() => {
      capturedTransients.foreach { case (k, v) =>
        if (tc.getTransient[AnyRef](k) == null) tc.putTransient(k, v)
      }
      capturedResponseHeaders.foreach { case (k, v) =>
        tc.addResponseHeader(k, v)
      }
    })
  }
}
