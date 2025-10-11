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
    AuditFieldName("@timestamp") -> AuditFieldValueDescriptor.Timestamp,
    AuditFieldName("trace") -> AuditFieldValueDescriptor.Nested(
      AuditFieldName("id") -> AuditFieldValueDescriptor.CorrelationId,
    ),
    AuditFieldName("elasticsearch") -> AuditFieldValueDescriptor.Nested(
      AuditFieldName("cluster") -> AuditFieldValueDescriptor.Nested(
        AuditFieldName("name") -> AuditFieldValueDescriptor.EsClusterName,
      ),
      AuditFieldName("index") -> AuditFieldValueDescriptor.Nested(
        AuditFieldName("name") -> AuditFieldValueDescriptor.InvolvedIndices,
      ),
      AuditFieldName("node") -> AuditFieldValueDescriptor.Nested(
        AuditFieldName("name") -> AuditFieldValueDescriptor.EsNodeName,
      ),
    ),
    AuditFieldName("user") -> AuditFieldValueDescriptor.Nested(
      AuditFieldName("name") -> AuditFieldValueDescriptor.User,
      AuditFieldName("effective") -> AuditFieldValueDescriptor.Nested(
        AuditFieldName("name") -> AuditFieldValueDescriptor.ImpersonatedByUser,
      ),
    ),
    AuditFieldName("event") -> AuditFieldValueDescriptor.Nested(
      AuditFieldName("action") -> AuditFieldValueDescriptor.Action,
      AuditFieldName("id") -> AuditFieldValueDescriptor.Id,
      AuditFieldName("outcome") -> AuditFieldValueDescriptor.FinalState,
      AuditFieldName("type") -> AuditFieldValueDescriptor.Type,
      AuditFieldName("duration") -> AuditFieldValueDescriptor.ProcessingDurationNanos,
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
        AuditFieldName("body") -> AuditFieldValueDescriptor.Nested(
          AuditFieldName("content") -> AuditFieldValueDescriptor.Content,
        ),
        AuditFieldName("bytes") -> AuditFieldValueDescriptor.ContentLengthInBytes,
        AuditFieldName("headers") -> AuditFieldValueDescriptor.Nested(
          AuditFieldName("x-forwarded-for") -> AuditFieldValueDescriptor.XForwardedForHttpHeader,
        ),
        AuditFieldName("method") -> AuditFieldValueDescriptor.HttpMethod,
      ),
    ),
    AuditFieldName("error") -> AuditFieldValueDescriptor.Nested(
      AuditFieldName("message") -> AuditFieldValueDescriptor.ErrorMessage,
      AuditFieldName("type") -> AuditFieldValueDescriptor.ErrorType,
    ),
    AuditFieldName("labels") -> AuditFieldValueDescriptor.Nested(
      AuditFieldName("acl_history") -> AuditFieldValueDescriptor.AclHistory,
      AuditFieldName("task_id") -> AuditFieldValueDescriptor.TaskId,
    ),
  )
}
