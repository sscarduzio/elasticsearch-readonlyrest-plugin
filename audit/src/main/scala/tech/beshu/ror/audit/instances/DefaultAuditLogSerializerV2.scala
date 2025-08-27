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
import tech.beshu.ror.audit._
import tech.beshu.ror.audit.AuditResponseContext.Verbosity
import tech.beshu.ror.audit.AuditSerializationHelper.{AllowedEventMode, AuditFieldName, AuditFieldValueDescriptor}
import tech.beshu.ror.audit.EnvironmentAwareAuditLogSerializer.environmentRelatedAuditFields
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV2.defaultV2AuditFields

class DefaultAuditLogSerializerV2(environmentContext: AuditEnvironmentContext) extends AuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] =
    AuditSerializationHelper.serialize(
      responseContext = responseContext,
      environmentContext = Some(environmentContext),
      fields = defaultV2AuditFields,
      allowedEventMode = AllowedEventMode.Include(Set(Verbosity.Info)),
    )

}

object DefaultAuditLogSerializerV2 {
  val defaultV2AuditFields: Map[AuditFieldName, AuditFieldValueDescriptor] = Map(
    AuditFieldName("match") -> AuditFieldValueDescriptor.IsMatched,
    AuditFieldName("block") -> AuditFieldValueDescriptor.Reason,
    AuditFieldName("id") -> AuditFieldValueDescriptor.Id,
    AuditFieldName("final_state") -> AuditFieldValueDescriptor.FinalState,
    AuditFieldName("@timestamp") -> AuditFieldValueDescriptor.Timestamp,
    AuditFieldName("correlation_id") -> AuditFieldValueDescriptor.CorrelationId,
    AuditFieldName("processingMillis") -> AuditFieldValueDescriptor.ProcessingDurationMillis,
    AuditFieldName("error_type") -> AuditFieldValueDescriptor.ErrorType,
    AuditFieldName("error_message") -> AuditFieldValueDescriptor.ErrorMessage,
    AuditFieldName("content_len") -> AuditFieldValueDescriptor.ContentLengthInBytes,
    AuditFieldName("content_len_kb") -> AuditFieldValueDescriptor.ContentLengthInKb,
    AuditFieldName("type") -> AuditFieldValueDescriptor.Type,
    AuditFieldName("origin") -> AuditFieldValueDescriptor.RemoteAddress,
    AuditFieldName("destination") -> AuditFieldValueDescriptor.LocalAddress,
    AuditFieldName("xff") -> AuditFieldValueDescriptor.XForwardedForHttpHeader,
    AuditFieldName("task_id") -> AuditFieldValueDescriptor.TaskId,
    AuditFieldName("req_method") -> AuditFieldValueDescriptor.HttpMethod,
    AuditFieldName("headers") -> AuditFieldValueDescriptor.HttpHeaderNames,
    AuditFieldName("path") -> AuditFieldValueDescriptor.HttpPath,
    AuditFieldName("user") -> AuditFieldValueDescriptor.User,
    AuditFieldName("impersonated_by") -> AuditFieldValueDescriptor.ImpersonatedByUser,
    AuditFieldName("action") -> AuditFieldValueDescriptor.Action,
    AuditFieldName("indices") -> AuditFieldValueDescriptor.InvolvedIndices,
    AuditFieldName("acl_history") -> AuditFieldValueDescriptor.AclHistory
  ) ++ environmentRelatedAuditFields
}
