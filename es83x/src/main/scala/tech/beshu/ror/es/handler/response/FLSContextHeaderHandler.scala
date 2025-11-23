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

import cats.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.headerValues.transientFieldsToHeaderValue
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.implicits.*

object FLSContextHeaderHandler extends RequestIdAwareLogging {

  def addContextHeader(threadPool: ThreadPool,
                       fieldsRestrictions: FieldsRestrictions)
                      (implicit requestId: RequestContext.Id): Unit = {
    val threadContext = threadPool.getThreadContext
    val header = createContextHeader(fieldsRestrictions)
    Option(threadContext.getHeader(header.name.value.value)) match {
      case None =>
        implicit val show = headerShow
        logger.debug(s"Adding thread context header required by lucene. Header: '${header.show}'")
        threadContext.putHeader(header.name.value.value, header.value.value)
      case Some(_) =>
    }
  }

  private def createContextHeader(fieldsRestrictions: FieldsRestrictions) = {
    new Header(
      Name.transientFields,
      transientFieldsToHeaderValue.toRawValue(fieldsRestrictions)
    )
  }
}
