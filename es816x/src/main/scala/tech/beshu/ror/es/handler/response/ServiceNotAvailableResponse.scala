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
package tech.beshu.ror.es.handler.response

import org.elasticsearch.ElasticsearchException
import org.elasticsearch.rest.RestStatus
import tech.beshu.ror.accesscontrol.response.ServiceNotAvailableResponseContext
import tech.beshu.ror.accesscontrol.response.ServiceNotAvailableResponseContext.ResponseCreator

import scala.jdk.CollectionConverters.*

final class ServiceNotAvailableResponse private(context: ServiceNotAvailableResponseContext)
  extends ElasticsearchException(context.responseMessage) {

  addMetadata("es.due_to", context.causes.asJava)

  override def status(): RestStatus = RestStatus.SERVICE_UNAVAILABLE
}

object ServiceNotAvailableResponse extends ResponseCreator[ServiceNotAvailableResponse] {

  override def create(context: ServiceNotAvailableResponseContext): ServiceNotAvailableResponse =
    new ServiceNotAvailableResponse(context)
}
