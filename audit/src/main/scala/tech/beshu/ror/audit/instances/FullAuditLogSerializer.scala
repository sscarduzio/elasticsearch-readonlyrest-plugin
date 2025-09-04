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
import tech.beshu.ror.audit.utils.AuditSerializationHelper
import tech.beshu.ror.audit.utils.AuditSerializationHelper.AllowedEventMode.IncludeAll
import tech.beshu.ror.audit.utils.AuditSerializationHelper.AuditFieldGroup._
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}

/**
 * Serializer for **full audit events**.
 *
 * - Includes `CommonFields` and `EsEnvironmentFields`.
 * - Serializes all events, including every `Allowed` request,
 * regardless of rule verbosity.
 *
 * Use this when you need complete coverage of all audit events.
 */
class FullAuditLogSerializer extends AuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] =
    AuditSerializationHelper.serialize(
      responseContext = responseContext,
      fieldGroups = Set(CommonFields, EsEnvironmentFields),
      allowedEventMode = IncludeAll
    )

}
