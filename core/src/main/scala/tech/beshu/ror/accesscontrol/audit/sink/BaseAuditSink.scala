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
import org.json.JSONObject
import tech.beshu.ror.accesscontrol.audit.AuditSerializer
import tech.beshu.ror.accesscontrol.audit.configurable.ConfigurableAuditLogSerializer
import tech.beshu.ror.accesscontrol.audit.ecs.EcsV1AuditLogSerializer
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.audit.AuditResponseContext

private[audit] abstract class BaseAuditSink(val name: Block.SinkName, auditSerializer: AuditSerializer)
    extends Block.AuditSink {

  final def submit(auditEvent: AuditResponseContext)(
      implicit requestId: RequestId
  ): Task[Unit] = {
    safeRunSerializer(auditEvent)
      .flatMap {
        case Some(serializedEvent) => submit(auditEvent, serializedEvent)
        case None                  => Task.unit
      }
  }

  def close(): Task[Unit]

  protected def submit(event: AuditResponseContext, serializedEvent: JSONObject)(
      implicit requestId: RequestId
  ): Task[Unit]

  private def safeRunSerializer(context: AuditResponseContext) = {
    auditSerializer match {
      case AuditSerializer.Delegating(serializer) =>
        Task.delay(serializer.onResponse(context))
      case AuditSerializer.Acl =>
        Task.delay(None)
      case AuditSerializer.EcsV1(allowedEventMode, includeFullRequestContent) =>
        Task.delay(EcsV1AuditLogSerializer.onResponse(context, allowedEventMode, includeFullRequestContent))
      case AuditSerializer.Configurable(allowedEventMode, fields) =>
        Task.delay(ConfigurableAuditLogSerializer.onResponse(context, allowedEventMode, fields))
    }
  }

}
