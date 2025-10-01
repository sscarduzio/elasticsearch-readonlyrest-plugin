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
    AuditFieldName("@timestamp" :: Nil) -> AuditFieldValueDescriptor.Timestamp,
    AuditFieldName("trace" :: "id" :: Nil) -> AuditFieldValueDescriptor.CorrelationId,
    AuditFieldName("elasticsearch" :: "cluster" :: "name" :: Nil) -> AuditFieldValueDescriptor.EsClusterName,
    AuditFieldName("elasticsearch" :: "index" :: "name" :: Nil) -> AuditFieldValueDescriptor.InvolvedIndices,
    AuditFieldName("elasticsearch" :: "node" :: "name" :: Nil) -> AuditFieldValueDescriptor.EsNodeName,
    AuditFieldName("user" :: "name" :: Nil) -> AuditFieldValueDescriptor.User,
    AuditFieldName("user" :: "effective" :: "name" :: Nil) -> AuditFieldValueDescriptor.ImpersonatedByUser,
    AuditFieldName("event" :: "action" :: Nil) -> AuditFieldValueDescriptor.Action,
    AuditFieldName("event" :: "id" :: Nil) -> AuditFieldValueDescriptor.Id,
    AuditFieldName("event" :: "outcome" :: Nil) -> AuditFieldValueDescriptor.FinalState,
    AuditFieldName("event" :: "type" :: Nil) -> AuditFieldValueDescriptor.Type,
    AuditFieldName("event" :: "duration" :: Nil) -> AuditFieldValueDescriptor.ProcessingDurationNanos,
    AuditFieldName("url" :: "path" :: Nil) -> AuditFieldValueDescriptor.HttpPath,
    AuditFieldName("source" :: "address" :: Nil) -> AuditFieldValueDescriptor.RemoteAddress,
    AuditFieldName("destination" :: "address" :: Nil) -> AuditFieldValueDescriptor.LocalAddress,
    AuditFieldName("http" :: "request" :: "body" :: "content" :: Nil) -> AuditFieldValueDescriptor.Content,
    AuditFieldName("http" :: "request" :: "bytes" :: Nil) -> AuditFieldValueDescriptor.ContentLengthInBytes,
    AuditFieldName("http" :: "request" :: "headers" :: "x-forwarded-for" :: Nil) -> AuditFieldValueDescriptor.XForwardedForHttpHeader,
    AuditFieldName("http" :: "request" :: "method" :: Nil) -> AuditFieldValueDescriptor.HttpMethod,
    AuditFieldName("error" :: "message" :: Nil) -> AuditFieldValueDescriptor.ErrorMessage,
    AuditFieldName("error" :: "type" :: Nil) -> AuditFieldValueDescriptor.ErrorType,
    AuditFieldName("labels" :: "acl_history" :: Nil) -> AuditFieldValueDescriptor.AclHistory,
    AuditFieldName("labels" :: "task_id" :: Nil) -> AuditFieldValueDescriptor.TaskId,
  )
}
