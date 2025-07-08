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
import tech.beshu.ror.audit.instances.BaseAuditLogSerializer.{AllowedEventSerializationMode, AuditValue}
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV1.defaultV1AuditFields
import tech.beshu.ror.audit.{AuditEnvironmentContext, AuditLogSerializer, AuditResponseContext}

class DefaultAuditLogSerializerV1(environmentContext: AuditEnvironmentContext) extends AuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] =
    BaseAuditLogSerializer.serialize(responseContext, environmentContext, defaultV1AuditFields, AllowedEventSerializationMode.SerializeOnlyEventsWithInfoLevelVerbose)

}

object DefaultAuditLogSerializerV1 {
  val defaultV1AuditFields: Map[String, AuditValue] = Map(
    "match" -> AuditValue.IsMatched,
    "block" -> AuditValue.Reason,
    "id" -> AuditValue.Id,
    "final_state" -> AuditValue.FinalState,
    "@timestamp" -> AuditValue.Timestamp,
    "correlation_id" -> AuditValue.CorrelationId,
    "processingMillis" -> AuditValue.ProcessingDurationMillis,
    "error_type" -> AuditValue.ErrorType,
    "error_message" -> AuditValue.ErrorMessage,
    "content_len" -> AuditValue.ContentLengthInBytes,
    "content_len_kb" -> AuditValue.ContentLengthInKb,
    "type" -> AuditValue.Type,
    "origin" -> AuditValue.RemoteAddress,
    "destination" -> AuditValue.LocalAddress,
    "xff" -> AuditValue.XForwardedForHttpHeader,
    "task_id" -> AuditValue.TaskId,
    "req_method" -> AuditValue.HttpMethod,
    "headers" -> AuditValue.HttpHeaderNames,
    "path" -> AuditValue.HttpPath,
    "user" -> AuditValue.User,
    "impersonated_by" -> AuditValue.ImpersonatedByUser,
    "action" -> AuditValue.Action,
    "indices" -> AuditValue.InvolvedIndices,
    "acl_history" -> AuditValue.AclHistory,
  )
}
