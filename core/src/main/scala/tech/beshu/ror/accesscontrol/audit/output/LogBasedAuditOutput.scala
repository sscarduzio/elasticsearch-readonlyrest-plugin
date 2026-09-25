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
package tech.beshu.ror.accesscontrol.audit.output

import monix.eval.Task
import org.apache.logging.log4j.{LogManager, Logger}
import tech.beshu.ror.accesscontrol.audit.AuditSerializer
import tech.beshu.ror.accesscontrol.domain.{AuditOutputName, RorAuditLoggerName}

private[audit] final class LogBasedAuditOutput(
    outputName: AuditOutputName,
    serializer: AuditSerializer,
    loggerName: RorAuditLoggerName
) extends TextBasedAuditOutput(outputName, serializer) {

  override protected val logger: Logger = LogManager.getLogger(loggerName.value.value)

  override def close(): Task[Unit] = Task.unit
}
