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
import tech.beshu.ror.audit.AuditResponseContext.Verbosity
import tech.beshu.ror.audit.utils.AuditSerializationHelper
import tech.beshu.ror.audit.utils.AuditSerializationHelper.AllowedEventMode.Include
import tech.beshu.ror.audit.utils.AuditSerializationHelper.AuditFieldGroup.{
  CommonFields,
  EsEnvironmentFields,
  FullRequestContentFields
}
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}

/**
 * Public alias for [[QueryAuditLogSerializerV2]].
 * - Captures full request content along with common and ES environment fields.
 * - Respects rule-defined verbosity for `Allowed` events:
 *   only serializes them if the corresponding rule allows logging at `Verbosity.Info`.
 * - Prefer this class name in configurations and client code for full-content auditing.
 * - Fields included:
 *   - `match` — whether the request matched a rule (boolean)
 *   - `block` — reason for blocking, if blocked (string)
 *   - `id` — audit event identifier (string)
 *   - `final_state` — final processing state (string)
 *   - `@timestamp` — event timestamp (ISO-8601 string)
 *   - `correlation_id` — correlation identifier for tracing (string)
 *   - `processingMillis` — request processing duration in milliseconds (number)
 *   - `error_type` — type of error, if any (string)
 *   - `error_message` — error message, if any (string)
 *   - `content_len` — request body size in bytes (number)
 *   - `content_len_kb` — request body size in kilobytes (number)
 *   - `type` — request type (string)
 *   - `origin` — client (remote) address (string)
 *   - `destination` — server (local) address (string)
 *   - `xff` — `X-Forwarded-For` HTTP header value (string)
 *   - `task_id` — Elasticsearch task ID (number)
 *   - `req_method` — HTTP request method (string)
 *   - `headers` — HTTP header names (array of strings)
 *   - `path` — HTTP request path (string)
 *   - `user` — authenticated user (string)
 *   - `impersonated_by` — impersonating user, if applicable (string)
 *   - `action` — Elasticsearch action name (string)
 *   - `indices` — indices involved in the request (array of strings)
 *   - `acl_history` — access control evaluation history (string)
 *   - `es_node_name` — Elasticsearch node name (string)
 *   - `es_cluster_name` — Elasticsearch cluster name (string)
 *   - `content` — full request body (string)
 */
class QueryAuditLogSerializer extends QueryAuditLogSerializerV2

/**
 * Serializer for audit events (V2) that is aware of **rule-defined verbosity**
 * and includes **full request content**.
 * - Serializes all non-Allowed events.
 * - Serializes `Allowed` events only if the corresponding rule
 *   specifies that they should be logged at `Verbosity.Info`.
 * - Recommended when capturing the full request content along with
 *   cluster and node context is needed.
 * - Fields included:
 *   - `match` — whether the request matched a rule (boolean)
 *   - `block` — reason for blocking, if blocked (string)
 *   - `id` — audit event identifier (string)
 *   - `final_state` — final processing state (string)
 *   - `@timestamp` — event timestamp (ISO-8601 string)
 *   - `correlation_id` — correlation identifier for tracing (string)
 *   - `processingMillis` — request processing duration in milliseconds (number)
 *   - `error_type` — type of error, if any (string)
 *   - `error_message` — error message, if any (string)
 *   - `content_len` — request body size in bytes (number)
 *   - `content_len_kb` — request body size in kilobytes (number)
 *   - `type` — request type (string)
 *   - `origin` — client (remote) address (string)
 *   - `destination` — server (local) address (string)
 *   - `xff` — `X-Forwarded-For` HTTP header value (string)
 *   - `task_id` — Elasticsearch task ID (number)
 *   - `req_method` — HTTP request method (string)
 *   - `headers` — HTTP header names (array of strings)
 *   - `path` — HTTP request path (string)
 *   - `user` — authenticated user (string)
 *   - `impersonated_by` — impersonating user, if applicable (string)
 *   - `action` — Elasticsearch action name (string)
 *   - `indices` — indices involved in the request (array of strings)
 *   - `acl_history` — access control evaluation history (string)
 *   - `es_node_name` — Elasticsearch node name (string)
 *   - `es_cluster_name` — Elasticsearch cluster name (string)
 *   - `content` — full request body (string)
 */
class QueryAuditLogSerializerV2 extends AuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] =
    AuditSerializationHelper.serialize(
      responseContext = responseContext,
      fieldGroups = Set(CommonFields, EsEnvironmentFields, FullRequestContentFields),
      allowedEventMode = Include(Set(Verbosity.Info))
    )

}

/**
 * Serializer for audit events (V1) that is aware of **rule-defined verbosity**
 * and includes **full request content**.
 * - Serializes all non-Allowed events.
 * - Serializes `Allowed` events only if the corresponding rule
 *   specifies that they should be logged at `Verbosity.Info`.
 * - Recommended when capturing the full request content is important,
 *   without cluster context.
 * - Fields included:
 *   - `match` — whether the request matched a rule (boolean)
 *   - `block` — reason for blocking, if blocked (string)
 *   - `id` — audit event identifier (string)
 *   - `final_state` — final processing state (string)
 *   - `@timestamp` — event timestamp (ISO-8601 string)
 *   - `correlation_id` — correlation identifier for tracing (string)
 *   - `processingMillis` — request processing duration in milliseconds (number)
 *   - `error_type` — type of error, if any (string)
 *   - `error_message` — error message, if any (string)
 *   - `content_len` — request body size in bytes (number)
 *   - `content_len_kb` — request body size in kilobytes (number)
 *   - `type` — request type (string)
 *   - `origin` — client (remote) address (string)
 *   - `destination` — server (local) address (string)
 *   - `xff` — `X-Forwarded-For` HTTP header value (string)
 *   - `task_id` — Elasticsearch task ID (number)
 *   - `req_method` — HTTP request method (string)
 *   - `headers` — HTTP header names (array of strings)
 *   - `path` — HTTP request path (string)
 *   - `user` — authenticated user (string)
 *   - `impersonated_by` — impersonating user, if applicable (string)
 *   - `action` — Elasticsearch action name (string)
 *   - `indices` — indices involved in the request (array of strings)
 *   - `acl_history` — access control evaluation history (string)
 *   - `content` — full request body (string)
 */
class QueryAuditLogSerializerV1 extends AuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] =
    AuditSerializationHelper.serialize(
      responseContext = responseContext,
      fieldGroups = Set(CommonFields, FullRequestContentFields),
      allowedEventMode = Include(Set(Verbosity.Info))
    )

}
