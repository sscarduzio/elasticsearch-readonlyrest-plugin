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
import tech.beshu.ror.accesscontrol.domain.RorAuditDataStream
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}
import tech.beshu.ror.es.DataStreamBasedAuditSinkService

private[audit] final class EsDataStreamBasedAuditSink private(serializer: AuditLogSerializer,
                                                              rorAuditDataStream: RorAuditDataStream,
                                                              auditSinkService: DataStreamBasedAuditSinkService)
  extends BaseAuditSink(serializer) {

  override protected def submit(event: AuditResponseContext, serializedEvent: JSONObject): Task[Unit] = Task {
    auditSinkService.submit(
      dataStreamName = rorAuditDataStream.dataStream,
      documentId = event.requestContext.id,
      jsonRecord = serializedEvent.toString
    )
  }

  override def close(): Task[Unit] = Task.delay(auditSinkService.close())

}

object EsDataStreamBasedAuditSink {
  def create(serializer: AuditLogSerializer,
             rorAuditDataStream: RorAuditDataStream,
             auditSinkService: DataStreamBasedAuditSinkService): Task[EsDataStreamBasedAuditSink] = {
    auditSinkService
      .dataStreamCreator
      .createIfNotExists(rorAuditDataStream)
      .flatMap {
        case Right(()) => Task.delay(new EsDataStreamBasedAuditSink(serializer, rorAuditDataStream, auditSinkService))
        case Left(errorMsg) => Task.raiseError(new IllegalStateException(errorMsg))
      }
  }
}
