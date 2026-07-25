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
package tech.beshu.ror.accesscontrol.audit.sink

import monix.eval.Task
import org.apache.logging.log4j.Logger
import org.json.JSONObject
import tech.beshu.ror.accesscontrol.audit.acl.AclAuditLogSerializer
import tech.beshu.ror.accesscontrol.audit.{AuditSerializer, JsonAuditSerializer}
import tech.beshu.ror.accesscontrol.domain.{RequestId, SinkName}
import tech.beshu.ror.audit.AuditResponseContext

private[audit] abstract class TextBasedAuditSink(val name: SinkName, serializer: AuditSerializer) extends AuditSink {

  protected val logger: Logger

  final def submit(event: AuditResponseContext)(
      implicit requestId: RequestId
  ): Task[Unit] = Task {
    serializer match {
      case AuditSerializer.Acl =>
        AclAuditLogSerializer
          .format(event, logger.isDebugEnabled)
          .foreach(msg => logger.info(s"[${requestId.value}] $msg"))
      case other: JsonAuditSerializer =>
        other.toJsonObject(event).foreach(log)
    }
  }

  private def log(json: JSONObject): Unit = logger.info(json.toString(0))

  def close(): Task[Unit]
}
