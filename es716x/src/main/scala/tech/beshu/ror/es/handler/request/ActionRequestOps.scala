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
package tech.beshu.ror.es.handler.request

import org.elasticsearch.action.ActionRequest
import tech.beshu.ror.es.handler.request.context.types.datastreams.ReflectionBasedDataStreamsEsRequestContext

object ActionRequestOps {

  implicit class RequestOps(val actionRequest: ActionRequest) extends AnyVal {

    def notDataStreamRelated: Boolean = {
      !RequestOps
        .dataStreamRequests
        .contains(actionRequest.getClass.getCanonicalName)
    }
  }

  private object RequestOps {
    private val dataStreamRequests: Set[String] =
      ReflectionBasedDataStreamsEsRequestContext.supportedActionRequests.map(_.value)
  }

}
