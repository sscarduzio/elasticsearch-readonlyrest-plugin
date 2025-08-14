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
package tech.beshu.ror.audit.instances

import org.json.JSONObject
import tech.beshu.ror.audit.BaseAuditLogSerializer.AllowedEventSerializationMode
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV1.defaultV1AuditFields
import tech.beshu.ror.audit._

class DefaultAuditLogSerializerV1(environmentContext: AuditEnvironmentContext) extends AuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] =
    BaseAuditLogSerializer.serialize(responseContext, environmentContext, defaultV1AuditFields, AllowedEventSerializationMode.SerializeOnlyAllowedEventsWithInfoLevelVerbose)

}

object DefaultAuditLogSerializerV1 {
  val defaultV1AuditFields: Map[String, AuditFieldValue] = Map(
    "match" -> AuditFieldValue(AuditFieldValuePlaceholder.IsMatched),
    "block" -> AuditFieldValue(AuditFieldValuePlaceholder.Reason),
    "id" -> AuditFieldValue(AuditFieldValuePlaceholder.Id),
    "final_state" -> AuditFieldValue(AuditFieldValuePlaceholder.FinalState),
    "@timestamp" -> AuditFieldValue(AuditFieldValuePlaceholder.Timestamp),
    "correlation_id" -> AuditFieldValue(AuditFieldValuePlaceholder.CorrelationId),
    "processingMillis" -> AuditFieldValue(AuditFieldValuePlaceholder.ProcessingDurationMillis),
    "error_type" -> AuditFieldValue(AuditFieldValuePlaceholder.ErrorType),
    "error_message" -> AuditFieldValue(AuditFieldValuePlaceholder.ErrorMessage),
    "content_len" -> AuditFieldValue(AuditFieldValuePlaceholder.ContentLengthInBytes),
    "content_len_kb" -> AuditFieldValue(AuditFieldValuePlaceholder.ContentLengthInKb),
    "type" -> AuditFieldValue(AuditFieldValuePlaceholder.Type),
    "origin" -> AuditFieldValue(AuditFieldValuePlaceholder.RemoteAddress),
    "destination" -> AuditFieldValue(AuditFieldValuePlaceholder.LocalAddress),
    "xff" -> AuditFieldValue(AuditFieldValuePlaceholder.XForwardedForHttpHeader),
    "task_id" -> AuditFieldValue(AuditFieldValuePlaceholder.TaskId),
    "req_method" -> AuditFieldValue(AuditFieldValuePlaceholder.HttpMethod),
    "headers" -> AuditFieldValue(AuditFieldValuePlaceholder.HttpHeaderNames),
    "path" -> AuditFieldValue(AuditFieldValuePlaceholder.HttpPath),
    "user" -> AuditFieldValue(AuditFieldValuePlaceholder.User),
    "impersonated_by" -> AuditFieldValue(AuditFieldValuePlaceholder.ImpersonatedByUser),
    "action" -> AuditFieldValue(AuditFieldValuePlaceholder.Action),
    "indices" -> AuditFieldValue(AuditFieldValuePlaceholder.InvolvedIndices),
    "acl_history" -> AuditFieldValue(AuditFieldValuePlaceholder.AclHistory)
  )
}
