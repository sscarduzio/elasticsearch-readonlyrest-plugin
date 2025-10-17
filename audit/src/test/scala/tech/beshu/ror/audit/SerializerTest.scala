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
package tech.beshu.ror.audit

import org.json.JSONObject
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.audit.instances._

import java.time.Instant
import scala.annotation.nowarn
import scala.jdk.CollectionConverters._

class SerializerTest extends AnyWordSpec {

  "Serializer" when {
    "serialized event contains only expected fields" when {
      "QueryAuditLogSerializer" in {
        testSerializerFieldsWithTypes(
          serializer = new QueryAuditLogSerializer,
          expectedFieldsWithTypes = Map(
            "match" -> "boolean",
            "block" -> "string",
            "id" -> "string",
            "final_state" -> "string",
            "@timestamp" -> "string",
            "correlation_id" -> "string",
            "processingMillis" -> "number",
            "content_len" -> "number",
            "content_len_kb" -> "number",
            "type" -> "string",
            "origin" -> "string",
            "destination" -> "string",
            "task_id" -> "number",
            "req_method" -> "string",
            "headers" -> "array",
            "path" -> "string",
            "user" -> "string",
            "logged_user" -> "string",
            "presented_identity" -> "string",
            "action" -> "string",
            "indices" -> "array",
            "acl_history" -> "string",
            "es_node_name" -> "string",
            "es_cluster_name" -> "string",
            "content" -> "string",
          )
        )
      }
      "QueryAuditLogSerializerV2" in {
        testSerializerFieldsWithTypes(
          serializer = new QueryAuditLogSerializerV2,
          expectedFieldsWithTypes = Map(
            "match" -> "boolean",
            "block" -> "string",
            "id" -> "string",
            "final_state" -> "string",
            "@timestamp" -> "string",
            "correlation_id" -> "string",
            "processingMillis" -> "number",
            "content_len" -> "number",
            "content_len_kb" -> "number",
            "type" -> "string",
            "origin" -> "string",
            "destination" -> "string",
            "task_id" -> "number",
            "req_method" -> "string",
            "headers" -> "array",
            "path" -> "string",
            "user" -> "string",
            "logged_user" -> "string",
            "presented_identity" -> "string",
            "action" -> "string",
            "indices" -> "array",
            "acl_history" -> "string",
            "es_node_name" -> "string",
            "es_cluster_name" -> "string",
            "content" -> "string",
          )
        )
      }
      "QueryAuditLogSerializerV1" in {
        testSerializerFieldsWithTypes(
          serializer = new QueryAuditLogSerializerV1,
          expectedFieldsWithTypes = Map(
            "match" -> "boolean",
            "block" -> "string",
            "id" -> "string",
            "final_state" -> "string",
            "@timestamp" -> "string",
            "correlation_id" -> "string",
            "processingMillis" -> "number",
            "content_len" -> "number",
            "content_len_kb" -> "number",
            "type" -> "string",
            "origin" -> "string",
            "destination" -> "string",
            "task_id" -> "number",
            "req_method" -> "string",
            "headers" -> "array",
            "path" -> "string",
            "user" -> "string",
            "logged_user" -> "string",
            "presented_identity" -> "string",
            "action" -> "string",
            "indices" -> "array",
            "acl_history" -> "string",
            "content" -> "string",
          )
        )
      }
      "FullAuditLogWithQuerySerializer" in {
        testSerializerFieldsWithTypes(
          serializer = new FullAuditLogWithQuerySerializer,
          expectedFieldsWithTypes = Map(
            "match" -> "boolean",
            "block" -> "string",
            "id" -> "string",
            "final_state" -> "string",
            "@timestamp" -> "string",
            "correlation_id" -> "string",
            "processingMillis" -> "number",
            "content_len" -> "number",
            "content_len_kb" -> "number",
            "type" -> "string",
            "origin" -> "string",
            "destination" -> "string",
            "task_id" -> "number",
            "req_method" -> "string",
            "headers" -> "array",
            "path" -> "string",
            "user" -> "string",
            "logged_user" -> "string",
            "presented_identity" -> "string",
            "action" -> "string",
            "indices" -> "array",
            "acl_history" -> "string",
            "es_node_name" -> "string",
            "es_cluster_name" -> "string",
            "content" -> "string",
          )
        )
      }
      "FullAuditLogSerializer" in {
        testSerializerFieldsWithTypes(
          serializer = new FullAuditLogSerializer,
          expectedFieldsWithTypes = Map(
            "match" -> "boolean",
            "block" -> "string",
            "id" -> "string",
            "final_state" -> "string",
            "@timestamp" -> "string",
            "correlation_id" -> "string",
            "processingMillis" -> "number",
            "content_len" -> "number",
            "content_len_kb" -> "number",
            "type" -> "string",
            "origin" -> "string",
            "destination" -> "string",
            "task_id" -> "number",
            "req_method" -> "string",
            "headers" -> "array",
            "path" -> "string",
            "user" -> "string",
            "logged_user" -> "string",
            "presented_identity" -> "string",
            "action" -> "string",
            "indices" -> "array",
            "acl_history" -> "string",
            "es_node_name" -> "string",
            "es_cluster_name" -> "string",
          )
        )
      }
      "BlockVerbosityAwareAuditLogSerializer" in {
        testSerializerFieldsWithTypes(
          serializer = new BlockVerbosityAwareAuditLogSerializer,
          expectedFieldsWithTypes = Map(
            "match" -> "boolean",
            "block" -> "string",
            "id" -> "string",
            "final_state" -> "string",
            "@timestamp" -> "string",
            "correlation_id" -> "string",
            "processingMillis" -> "number",
            "content_len" -> "number",
            "content_len_kb" -> "number",
            "type" -> "string",
            "origin" -> "string",
            "destination" -> "string",
            "task_id" -> "number",
            "req_method" -> "string",
            "headers" -> "array",
            "path" -> "string",
            "user" -> "string",
            "logged_user" -> "string",
            "presented_identity" -> "string",
            "action" -> "string",
            "indices" -> "array",
            "acl_history" -> "string",
            "es_node_name" -> "string",
            "es_cluster_name" -> "string",
          )
        )
      }
      "DefaultAuditLogSerializerV2" in {
        testSerializerFieldsWithTypes(
          serializer = new DefaultAuditLogSerializerV2 @nowarn("cat=deprecation"),
          expectedFieldsWithTypes = Map(
            "match" -> "boolean",
            "block" -> "string",
            "id" -> "string",
            "final_state" -> "string",
            "@timestamp" -> "string",
            "correlation_id" -> "string",
            "processingMillis" -> "number",
            "content_len" -> "number",
            "content_len_kb" -> "number",
            "type" -> "string",
            "origin" -> "string",
            "destination" -> "string",
            "task_id" -> "number",
            "req_method" -> "string",
            "headers" -> "array",
            "path" -> "string",
            "user" -> "string",
            "logged_user" -> "string",
            "presented_identity" -> "string",
            "action" -> "string",
            "indices" -> "array",
            "acl_history" -> "string",
            "es_node_name" -> "string",
            "es_cluster_name" -> "string",
          )
        )
      }
      "DefaultAuditLogSerializerV1" in {
        testSerializerFieldsWithTypes(
          serializer = new DefaultAuditLogSerializerV1 @nowarn("cat=deprecation"),
          expectedFieldsWithTypes = Map(
            "match" -> "boolean",
            "block" -> "string",
            "id" -> "string",
            "final_state" -> "string",
            "@timestamp" -> "string",
            "correlation_id" -> "string",
            "processingMillis" -> "number",
            "content_len" -> "number",
            "content_len_kb" -> "number",
            "type" -> "string",
            "origin" -> "string",
            "destination" -> "string",
            "task_id" -> "number",
            "req_method" -> "string",
            "headers" -> "array",
            "path" -> "string",
            "user" -> "string",
            "logged_user" -> "string",
            "presented_identity" -> "string",
            "action" -> "string",
            "indices" -> "array",
            "acl_history" -> "string",
          ),
        )
      }
    }
  }

  private def testSerializerFieldsWithTypes(serializer: AuditLogSerializer,
                                            expectedFieldsWithTypes: Map[String, String]): Unit = {
    val serialized = serializer.onResponse(AuditResponseContext.Forbidden(DummyAuditRequestContext)).get
    val entryFields = serialized.keySet.asScala.toSet

    if (entryFields != expectedFieldsWithTypes.keySet) {
      fail(s"Serialized event does not contains exactly fields that were expected")
    } else {
      expectedFieldsWithTypes.foreach { case (fieldName, expectedType) =>
        expectedType match {
          case "string" => noException should be thrownBy serialized.getString(fieldName)
          case "boolean" => noException should be thrownBy serialized.getBoolean(fieldName)
          case "number" => noException should be thrownBy serialized.getDouble(fieldName)
          case "array" =>
            val value = serialized.get(fieldName)
            value match {
              case _: org.json.JSONArray => succeed
              case s: java.util.Collection[_] => noException should be thrownBy new org.json.JSONArray(s)
              case other => fail(s"Expected '$fieldName' to be JSONArray, but got ${other.getClass.getName}: $other")
            }
          case other => fail(s"Unknown expected type: $other")
        }
      }
    }
  }

}

private object DummyAuditRequestContext extends AuditRequestContext {
  override def timestamp: Instant = Instant.now()

  override def id: String = ""

  override def correlationId: String = ""

  override def indices: Set[String] = Set.empty

  override def action: String = ""

  override def headers: Map[String, String] = Map.empty

  override def requestHeaders: Headers = Headers(Map.empty)

  override def uriPath: String = ""

  override def history: String = ""

  override def content: String = ""

  override def contentLength: Integer = 0

  override def remoteAddress: String = ""

  override def localAddress: String = ""

  override def `type`: String = ""

  override def taskId: Long = 0

  override def httpMethod: String = ""

  override def loggedInUserName: Option[String] = Some("logged_user")

  override def impersonatedByUserName: Option[String] = None

  override def involvesIndices: Boolean = false

  override def attemptedUserName: Option[String] = None

  override def rawAuthHeader: Option[String] = None

  override def generalAuditEvents: JSONObject = new JSONObject

  override def auditEnvironmentContext: AuditEnvironmentContext = new AuditEnvironmentContext {
    override def esNodeName: String = "testEsNode"

    override def esClusterName: String = "testEsCluster"
  }
}
