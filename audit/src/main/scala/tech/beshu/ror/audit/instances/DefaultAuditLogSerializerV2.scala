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
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV2.defaultV2AuditFields
import tech.beshu.ror.audit.{AuditEnvironmentContext, AuditLogSerializer, AuditResponseContext}

class DefaultAuditLogSerializerV2(environmentContext: AuditEnvironmentContext) extends AuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] =
    BaseAuditLogSerializer.serialize(responseContext, environmentContext, defaultV2AuditFields, AllowedEventSerializationMode.SerializeOnlyEventsWithInfoLevelVerbose)

}

object DefaultAuditLogSerializerV2 {
  val defaultV2AuditFields: Map[String, AuditValue] =
    DefaultAuditLogSerializerV1.defaultV1AuditFields ++ Map(
      "es_node_name" -> AuditValue.EsNodeName,
      "es_cluster_name" -> AuditValue.EsClusterName,
    )
}
