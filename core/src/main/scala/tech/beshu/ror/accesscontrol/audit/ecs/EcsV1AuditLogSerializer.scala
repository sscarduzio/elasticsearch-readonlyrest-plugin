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
package tech.beshu.ror.accesscontrol.audit.ecs

import org.json.JSONObject
import tech.beshu.ror.accesscontrol.audit.AuditFieldUtils.*
import tech.beshu.ror.accesscontrol.audit.ecs.EcsV1AuditLogSerializer.*
import tech.beshu.ror.audit.utils.AuditSerializationHelper
import tech.beshu.ror.audit.utils.AuditSerializationHelper.{AllowedEventMode, AuditFieldPath, AuditFieldValueDescriptor}
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}

class EcsV1AuditLogSerializer(val allowedEventMode: AllowedEventMode) extends AuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] = {
    AuditSerializationHelper.serialize(responseContext, auditFields, allowedEventMode)
  }

}

object EcsV1AuditLogSerializer {
  private val auditFields: Map[AuditFieldPath, AuditFieldValueDescriptor] = fields(
    withPrefix("ecs")(
      // Schema defined by EcsV1AuditLogSerializer is ECS 1.6.0 compliant and does not use newer features
      // introduced by later versions (https://www.elastic.co/guide/en/ecs/1.6/ecs-field-reference.html)
      AuditFieldPath("version") -> AuditFieldValueDescriptor.StaticText("1.6.0"),
    ),
    withPrefix("trace")(
      AuditFieldPath("id") -> AuditFieldValueDescriptor.CorrelationId,
    ),
    withPrefix("url")(
      AuditFieldPath("path") -> AuditFieldValueDescriptor.HttpPath,
    ),
    withPrefix("source")(
      AuditFieldPath("address") -> AuditFieldValueDescriptor.RemoteAddress,
    ),
    withPrefix("destination")(
      AuditFieldPath("address") -> AuditFieldValueDescriptor.LocalAddress,
    ),
    withPrefix("http")(
      withPrefix("request")(
        AuditFieldPath("method") -> AuditFieldValueDescriptor.HttpMethod,
        withPrefix("body")(
          AuditFieldPath("content") -> AuditFieldValueDescriptor.Content,
          AuditFieldPath("bytes") -> AuditFieldValueDescriptor.ContentLengthInBytes,
        ),
      ),
    ),
    withPrefix("user")(
      AuditFieldPath("name") -> AuditFieldValueDescriptor.LoggedUser,
      withPrefix("effective")(
        AuditFieldPath("name") -> AuditFieldValueDescriptor.ImpersonatedByUser,
      ),
    ),
    withPrefix("event")(
      AuditFieldPath("id") -> AuditFieldValueDescriptor.Id,
      AuditFieldPath("duration") -> AuditFieldValueDescriptor.ProcessingDurationNanos,
      AuditFieldPath("action") -> AuditFieldValueDescriptor.Action,
      AuditFieldPath("reason") -> AuditFieldValueDescriptor.Type,
      AuditFieldPath("outcome") -> AuditFieldValueDescriptor.EcsEventOutcome,
    ),
    withPrefix("error")(
      AuditFieldPath("type") -> AuditFieldValueDescriptor.ErrorType,
      AuditFieldPath("message") -> AuditFieldValueDescriptor.ErrorMessage,
    ),
    withPrefix("labels")(
      AuditFieldPath("x_forwarded_for") -> AuditFieldValueDescriptor.XForwardedForHttpHeader,
      AuditFieldPath("es_cluster_name") -> AuditFieldValueDescriptor.EsClusterName,
      AuditFieldPath("es_node_name") -> AuditFieldValueDescriptor.EsNodeName,
      AuditFieldPath("es_task_id") -> AuditFieldValueDescriptor.TaskId,
      AuditFieldPath("ror_involved_indices") -> AuditFieldValueDescriptor.InvolvedIndices,
      AuditFieldPath("ror_acl_history") -> AuditFieldValueDescriptor.AclHistory,
      AuditFieldPath("ror_final_state") -> AuditFieldValueDescriptor.FinalState,
      AuditFieldPath("ror_detailed_reason") -> AuditFieldValueDescriptor.Reason,
    ),
  )
}
