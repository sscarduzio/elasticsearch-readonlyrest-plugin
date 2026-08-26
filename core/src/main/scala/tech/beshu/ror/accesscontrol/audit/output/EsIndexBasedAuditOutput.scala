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
import org.json.JSONObject
import tech.beshu.ror.accesscontrol.audit.JsonAuditSerializer
import tech.beshu.ror.accesscontrol.domain.{AuditOutputName, RequestId, RorAuditIndexTemplate}
import tech.beshu.ror.audit.AuditResponseContext
import tech.beshu.ror.es.services.IndexBasedAuditOutputService

import java.time.Clock

private[audit] final class EsIndexBasedAuditOutput private (
    outputName: AuditOutputName,
    serializer: JsonAuditSerializer,
    rorAuditIndexTemplate: RorAuditIndexTemplate,
    auditOutputService: IndexBasedAuditOutputService
)(
    implicit clock: Clock
) extends JsonBasedAuditOutput(outputName, serializer) {

  override protected def submit(event: AuditResponseContext, serializedEvent: JSONObject)(
      implicit requestId: RequestId
  ): Task[Unit] = Task {
    auditOutputService.submit(
      indexName = rorAuditIndexTemplate.indexName(clock.instant()),
      documentId = event.requestContext.id,
      jsonRecord = serializedEvent.toString
    )
  }

  override def close(): Task[Unit] = Task.delay(auditOutputService.close())
}

object EsIndexBasedAuditOutput {

  def apply(
      outputName: AuditOutputName,
      serializer: JsonAuditSerializer,
      indexTemplate: RorAuditIndexTemplate,
      auditOutputService: IndexBasedAuditOutputService
  )(
      implicit clock: Clock
  ): EsIndexBasedAuditOutput = {
    new EsIndexBasedAuditOutput(outputName, serializer, indexTemplate, auditOutputService)
  }

}
