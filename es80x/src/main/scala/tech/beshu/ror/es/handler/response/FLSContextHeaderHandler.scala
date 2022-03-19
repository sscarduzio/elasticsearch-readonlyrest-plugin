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

import cats.implicits._
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.headerValues.transientFieldsToHeaderValue
import tech.beshu.ror.accesscontrol.request.RequestContext

object FLSContextHeaderHandler extends Logging {

  def addContextHeader(threadPool: ThreadPool,
                       fieldsRestrictions: FieldsRestrictions,
                       requestId: RequestContext.Id): Unit = {
    val threadContext = threadPool.getThreadContext
    val header = createContextHeader(fieldsRestrictions)
    Option(threadContext.getHeader(header.name.value.value)) match {
      case None =>
        logger.debug(s"[${requestId.show}] Adding thread context header required by lucene. Header Value: '${header.value.value}'")
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
