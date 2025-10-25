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
import tech.beshu.ror.accesscontrol.audit.ecs.EcsV1AuditLogSerializer.fields
import tech.beshu.ror.audit.utils.AuditSerializationHelper
import tech.beshu.ror.audit.utils.AuditSerializationHelper.{AllowedEventMode, AuditFieldName, AuditFieldValueDescriptor}
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}

class EcsV1AuditLogSerializer(val allowedEventMode: AllowedEventMode) extends AuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] = {
    AuditSerializationHelper.serialize(responseContext, fields, allowedEventMode)
  }

}

object EcsV1AuditLogSerializer {
  private val fields: Map[AuditFieldName, AuditFieldValueDescriptor] = Map(
    AuditFieldName("ecs") -> AuditFieldValueDescriptor.Nested(
      // Schema defined by EcsV1AuditLogSerializer is ECS 1.4.0 compliant and does not use newer features
      // introduced by later versions (https://www.elastic.co/guide/en/ecs/1.4/ecs-field-reference.html)
      AuditFieldName("version") -> AuditFieldValueDescriptor.StaticText("1.4.0"),
    ),
    AuditFieldName("trace") -> AuditFieldValueDescriptor.Nested(
      AuditFieldName("id") -> AuditFieldValueDescriptor.CorrelationId,
    ),
    AuditFieldName("url") -> AuditFieldValueDescriptor.Nested(
      AuditFieldName("path") -> AuditFieldValueDescriptor.HttpPath,
    ),
    AuditFieldName("source") -> AuditFieldValueDescriptor.Nested(
      AuditFieldName("address") -> AuditFieldValueDescriptor.RemoteAddress,
    ),
    AuditFieldName("destination") -> AuditFieldValueDescriptor.Nested(
      AuditFieldName("address") -> AuditFieldValueDescriptor.LocalAddress,
    ),
    AuditFieldName("http") -> AuditFieldValueDescriptor.Nested(
      AuditFieldName("request") -> AuditFieldValueDescriptor.Nested(
        AuditFieldName("method") -> AuditFieldValueDescriptor.HttpMethod,
        AuditFieldName("body") -> AuditFieldValueDescriptor.Nested(
          AuditFieldName("content") -> AuditFieldValueDescriptor.Content,
          AuditFieldName("bytes") -> AuditFieldValueDescriptor.ContentLengthInBytes,
        ),
      ),
    ),
    AuditFieldName("user") -> AuditFieldValueDescriptor.Nested(
      AuditFieldName("name") -> AuditFieldValueDescriptor.User,
      AuditFieldName("effective") -> AuditFieldValueDescriptor.Nested(
        AuditFieldName("name") -> AuditFieldValueDescriptor.ImpersonatedByUser,
      ),
    ),
    AuditFieldName("event") -> AuditFieldValueDescriptor.Nested(
      AuditFieldName("id") -> AuditFieldValueDescriptor.Id,
      AuditFieldName("action") -> AuditFieldValueDescriptor.Action,
      AuditFieldName("type") -> AuditFieldValueDescriptor.Type,
      AuditFieldName("reason") -> AuditFieldValueDescriptor.FinalState,
      AuditFieldName("duration") -> AuditFieldValueDescriptor.ProcessingDurationNanos,
    ),
    AuditFieldName("error") -> AuditFieldValueDescriptor.Nested(
      AuditFieldName("type") -> AuditFieldValueDescriptor.ErrorType,
      AuditFieldName("message") -> AuditFieldValueDescriptor.ErrorMessage,
    ),
    AuditFieldName("labels") -> AuditFieldValueDescriptor.Nested(
      AuditFieldName("es_cluster_name") -> AuditFieldValueDescriptor.EsClusterName,
      AuditFieldName("es_node_name") -> AuditFieldValueDescriptor.EsNodeName,
      AuditFieldName("es_task_id") -> AuditFieldValueDescriptor.TaskId,
      AuditFieldName("involved_indices") -> AuditFieldValueDescriptor.InvolvedIndices,
      AuditFieldName("acl_history") -> AuditFieldValueDescriptor.AclHistory,
      AuditFieldName("x_forwarded_for") -> AuditFieldValueDescriptor.XForwardedForHttpHeader,
    ),
  )
}
