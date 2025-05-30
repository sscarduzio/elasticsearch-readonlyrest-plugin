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
import tech.beshu.ror.audit.{AuditEnvironmentContext, AuditResponseContext}

class DefaultAuditLogSerializerV2 extends DefaultAuditLogSerializerV1 {

  override def onResponse(responseContext: AuditResponseContext,
                          environmentContext: AuditEnvironmentContext): Option[JSONObject] = {
    lazy val additionalFields = Map(
      "es_node_name" -> environmentContext.esNodeName,
      "es_cluster_name" -> environmentContext.esClusterName
    )
    super.onResponse(responseContext, environmentContext)
      .map(additionalFields.foldLeft(_) { case (soFar, (key, value)) => soFar.put(key, value) })
  }
}
