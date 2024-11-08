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
package tech.beshu.ror.accesscontrol.logging

import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.audit.instances.{DefaultAuditLogSerializer, QueryAuditLogSerializer}
import tech.beshu.ror.commons
import tech.beshu.ror.implicits.*
import tech.beshu.ror.requestcontext.AuditLogSerializer

import scala.annotation.nowarn

@nowarn("cat=deprecation")
final class DeprecatedAuditLoggingDecorator[T](underlying: AuditLogSerializer[T])
  extends AuditLogSerializer[T]
    with Logging {

  private val deprecatedSerializerCanonicalName = underlying.getClass.getCanonicalName
  private val defaultSerializerCanonicalName = classOf[DefaultAuditLogSerializer].getCanonicalName
  private val querySerializerCanonicalName = classOf[QueryAuditLogSerializer].getCanonicalName

  override def createLoggableEntry(context: commons.ResponseContext): T = {
    logger.warn(s"you're using deprecated serializer ${deprecatedSerializerCanonicalName.show}, please use ${defaultSerializerCanonicalName.show}, or ${querySerializerCanonicalName.show} instead")
    underlying.createLoggableEntry(context)
  }
}