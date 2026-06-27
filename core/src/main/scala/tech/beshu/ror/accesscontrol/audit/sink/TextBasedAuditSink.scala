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
import tech.beshu.ror.accesscontrol.audit.CoreAuditSerializer.External
import tech.beshu.ror.accesscontrol.audit.{AclAuditLogSerializer, CoreAuditSerializer}
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.audit.AuditResponseContext

private[audit] abstract class TextBasedAuditSink(val name: Block.SinkName, serializer: CoreAuditSerializer)
    extends Block.AuditSink {

  protected val logger: Logger

  final def submit(event: AuditResponseContext)(
      implicit requestId: RequestId
  ): Task[Unit] = Task {
    serializer match {
      case External(s) =>
        s.onResponse(event).foreach(json => logger.info(json.toString))
      case s: AclAuditLogSerializer =>
        s.format(event, logger.isDebugEnabled).foreach(msg => logger.info(s"[${requestId.value}] $msg"))
    }
  }

  def close(): Task[Unit]
}
