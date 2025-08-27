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
package tech.beshu.ror.accesscontrol.audit.configurable

import org.json.JSONObject
import tech.beshu.ror.audit.AuditSerializationHelper.{AllowedEventMode, AuditFieldName, AuditFieldValueDescriptor}
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializer
import tech.beshu.ror.audit.{AuditEnvironmentContext, AuditResponseContext, AuditSerializationHelper}

class ConfigurableAuditLogSerializer(val environmentContext: AuditEnvironmentContext,
                                     val allowedEventMode: AllowedEventMode,
                                     val fields: Map[AuditFieldName, AuditFieldValueDescriptor]) extends DefaultAuditLogSerializer(environmentContext) {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] =
    AuditSerializationHelper.serialize(responseContext, Some(environmentContext), fields, allowedEventMode)

}
