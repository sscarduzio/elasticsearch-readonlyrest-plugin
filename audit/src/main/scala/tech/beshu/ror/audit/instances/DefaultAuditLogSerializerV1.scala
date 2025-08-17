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
import tech.beshu.ror.audit.*
import tech.beshu.ror.audit.AuditSerializationHelper.{AllowedEventSerializationMode, AuditFieldName}
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV1.defaultV1AuditFields

class DefaultAuditLogSerializerV1 extends AuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] =
    AuditSerializationHelper.serialize(
      responseContext = responseContext,
      environmentContext = None,
      fields = defaultV1AuditFields,
      allowedEventSerializationMode = AllowedEventSerializationMode.SerializeOnlyAllowedEventsWithInfoLevelVerbose
    )

}

object DefaultAuditLogSerializerV1 {
  val defaultV1AuditFields: Map[AuditFieldName, AuditFieldValue] = Map(
    AuditFieldName("match") -> AuditFieldValue.IsMatched,
    AuditFieldName("block") -> AuditFieldValue.Reason,
    AuditFieldName("id") -> AuditFieldValue.Id,
    AuditFieldName("final_state") -> AuditFieldValue.FinalState,
    AuditFieldName("@timestamp") -> AuditFieldValue.Timestamp,
    AuditFieldName("correlation_id") -> AuditFieldValue.CorrelationId,
    AuditFieldName("processingMillis") -> AuditFieldValue.ProcessingDurationMillis,
    AuditFieldName("error_type") -> AuditFieldValue.ErrorType,
    AuditFieldName("error_message") -> AuditFieldValue.ErrorMessage,
    AuditFieldName("content_len") -> AuditFieldValue.ContentLengthInBytes,
    AuditFieldName("content_len_kb") -> AuditFieldValue.ContentLengthInKb,
    AuditFieldName("type") -> AuditFieldValue.Type,
    AuditFieldName("origin") -> AuditFieldValue.RemoteAddress,
    AuditFieldName("destination") -> AuditFieldValue.LocalAddress,
    AuditFieldName("xff") -> AuditFieldValue.XForwardedForHttpHeader,
    AuditFieldName("task_id") -> AuditFieldValue.TaskId,
    AuditFieldName("req_method") -> AuditFieldValue.HttpMethod,
    AuditFieldName("headers") -> AuditFieldValue.HttpHeaderNames,
    AuditFieldName("path") -> AuditFieldValue.HttpPath,
    AuditFieldName("user") -> AuditFieldValue.User,
    AuditFieldName("impersonated_by") -> AuditFieldValue.ImpersonatedByUser,
    AuditFieldName("action") -> AuditFieldValue.Action,
    AuditFieldName("indices") -> AuditFieldValue.InvolvedIndices,
    AuditFieldName("acl_history") -> AuditFieldValue.AclHistory
  )
}
