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
import tech.beshu.ror.accesscontrol.domain.{RequestId, RorAuditIndexTemplate, SinkName}
import tech.beshu.ror.audit.AuditResponseContext
import tech.beshu.ror.es.services.IndexBasedAuditSinkService

import java.time.Clock

private[audit] final class EsIndexBasedAuditSink private (
    sinkName: SinkName,
    serializer: AuditSerializer,
    rorAuditIndexTemplate: RorAuditIndexTemplate,
    auditSinkService: IndexBasedAuditSinkService
)(
    implicit clock: Clock
) extends BaseAuditSink(sinkName, serializer) {

  override protected def submit(event: AuditResponseContext, serializedEvent: JSONObject)(
      implicit requestId: RequestId
  ): Task[Unit] = Task {
    auditSinkService.submit(
      indexName = rorAuditIndexTemplate.indexName(clock.instant()),
      documentId = event.requestContext.id,
      jsonRecord = serializedEvent.toString
    )
  }

  override def close(): Task[Unit] = Task.delay(auditSinkService.close())
}

object EsIndexBasedAuditSink {

  def apply(
      sinkName: SinkName,
      serializer: AuditSerializer,
      indexTemplate: RorAuditIndexTemplate,
      auditSinkService: IndexBasedAuditSinkService
  )(
      implicit clock: Clock
  ): EsIndexBasedAuditSink = {
    new EsIndexBasedAuditSink(sinkName, serializer, indexTemplate, auditSinkService)
  }

}
