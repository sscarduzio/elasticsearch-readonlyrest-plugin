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
import tech.beshu.ror.audit.instances.BaseAuditLogSerializer.{AllowedEventSerializationMode, AuditFieldValue, AuditValuePlaceholder}
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV1.defaultV1AuditFields
import tech.beshu.ror.audit.{AuditEnvironmentContext, AuditLogSerializer, AuditResponseContext}

class DefaultAuditLogSerializerV1(environmentContext: AuditEnvironmentContext) extends AuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] =
    BaseAuditLogSerializer.serialize(responseContext, environmentContext, defaultV1AuditFields, AllowedEventSerializationMode.SerializeOnlyAllowedEventsWithInfoLevelVerbose)

}

object DefaultAuditLogSerializerV1 {
  val defaultV1AuditFields: Map[String, AuditFieldValue] = Map(
    "match" -> AuditFieldValue(AuditValuePlaceholder.IsMatched),
    "block" -> AuditFieldValue(AuditValuePlaceholder.Reason),
    "id" -> AuditFieldValue(AuditValuePlaceholder.Id),
    "final_state" -> AuditFieldValue(AuditValuePlaceholder.FinalState),
    "@timestamp" -> AuditFieldValue(AuditValuePlaceholder.Timestamp),
    "correlation_id" -> AuditFieldValue(AuditValuePlaceholder.CorrelationId),
    "processingMillis" -> AuditFieldValue(AuditValuePlaceholder.ProcessingDurationMillis),
    "error_type" -> AuditFieldValue(AuditValuePlaceholder.ErrorType),
    "error_message" -> AuditFieldValue(AuditValuePlaceholder.ErrorMessage),
    "content_len" -> AuditFieldValue(AuditValuePlaceholder.ContentLengthInBytes),
    "content_len_kb" -> AuditFieldValue(AuditValuePlaceholder.ContentLengthInKb),
    "type" -> AuditFieldValue(AuditValuePlaceholder.Type),
    "origin" -> AuditFieldValue(AuditValuePlaceholder.RemoteAddress),
    "destination" -> AuditFieldValue(AuditValuePlaceholder.LocalAddress),
    "xff" -> AuditFieldValue(AuditValuePlaceholder.XForwardedForHttpHeader),
    "task_id" -> AuditFieldValue(AuditValuePlaceholder.TaskId),
    "req_method" -> AuditFieldValue(AuditValuePlaceholder.HttpMethod),
    "headers" -> AuditFieldValue(AuditValuePlaceholder.HttpHeaderNames),
    "path" -> AuditFieldValue(AuditValuePlaceholder.HttpPath),
    "user" -> AuditFieldValue(AuditValuePlaceholder.User),
    "impersonated_by" -> AuditFieldValue(AuditValuePlaceholder.ImpersonatedByUser),
    "action" -> AuditFieldValue(AuditValuePlaceholder.Action),
    "indices" -> AuditFieldValue(AuditValuePlaceholder.InvolvedIndices),
    "acl_history" -> AuditFieldValue(AuditValuePlaceholder.AclHistory),
  )
}
