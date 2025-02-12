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
package tech.beshu.ror.accesscontrol.audit

import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import org.json.JSONObject
import tech.beshu.ror.accesscontrol.audit.DataStreamAuditSinkCreator.DataStreamSettings
import tech.beshu.ror.accesscontrol.domain.RorAuditDataStream
import tech.beshu.ror.audit.instances.{DefaultAuditLogSerializer, FieldType, QueryAuditLogSerializer}
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}
import tech.beshu.ror.es.{AuditSinkService, DataStreamAndIndexBasedAuditSinkService}

private[audit] final class EsDataStreamBasedAuditSink private(serializer: AuditLogSerializer,
                                                              rorAuditDataStream: RorAuditDataStream,
                                                              auditSinkService: AuditSinkService)
  extends BaseAuditSink(serializer) {

  override protected def submit(event: AuditResponseContext, serializedEvent: JSONObject): Task[Unit] = Task {
    auditSinkService.submit(
      indexName = rorAuditDataStream.dataStream.value.value,
      documentId = event.requestContext.id,
      jsonRecord = serializedEvent.toString
    )
  }

  override def close(): Task[Unit] = Task.delay(auditSinkService.close())

}

object EsDataStreamBasedAuditSink extends Logging {
  def create(serializer: AuditLogSerializer,
             rorAuditDataStream: RorAuditDataStream,
             auditSinkService: DataStreamAndIndexBasedAuditSinkService): Task[EsDataStreamBasedAuditSink] = {
    val defaultSettings = DataStreamSettings(rorAuditDataStream.dataStream, mappingsForSerializer(serializer))
    auditSinkService
      .dataStreamCreator
      .createIfNotExists(defaultSettings)
      .map((_: Unit) => new EsDataStreamBasedAuditSink(serializer, rorAuditDataStream, auditSinkService))
  }

  private def mappingsForSerializer(serializer: AuditLogSerializer): Map[String, FieldType] = {
    serializer match {
      case _: QueryAuditLogSerializer => QueryAuditLogSerializer.defaultIndexedMappings
      case _: DefaultAuditLogSerializer => DefaultAuditLogSerializer.defaultIndexedMappings
      case _ =>
      // ES data streams require the timestamp field.
      // It would be hard to pass the additional field mappings through the ROR yaml config, so only the timestamp field is here
      Map("@timestamp" -> FieldType.Date)
    }
  }
}
