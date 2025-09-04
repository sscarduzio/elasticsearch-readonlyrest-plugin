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
import tech.beshu.ror.audit._
import tech.beshu.ror.audit.AuditResponseContext.Verbosity
import tech.beshu.ror.audit.utils.AuditSerializationHelper.AllowedEventMode.Include
import tech.beshu.ror.audit.utils.AuditSerializationHelper
import tech.beshu.ror.audit.utils.AuditSerializationHelper.AuditFieldGroup.{CommonFields, EsEnvironmentFields}

/**
 * Serializer for audit events (V2) that is aware of **rule-defined verbosity**.
 *
 * - Includes `CommonFields` plus `EsEnvironmentFields`
 * (cluster and node identifiers).
 * - Serializes all non-Allowed events.
 * - Serializes `Allowed` events only if the corresponding rule
 * specifies that they should be logged at `Verbosity.Info`.
 *
 * Recommended when node and cluster context are also required.
 */
class BlockVerbosityAwareAuditLogSerializerV2 extends DefaultAuditLogSerializerV2

/**
 * Base implementation for [[BlockVerbosityAwareAuditLogSerializerV2]].
 * Not intended for direct external use.
 */
class DefaultAuditLogSerializerV2 extends AuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] =
    AuditSerializationHelper.serialize(
      responseContext = responseContext,
      fieldGroups = Set(CommonFields, EsEnvironmentFields),
      allowedEventMode = Include(Set(Verbosity.Info))
    )

}
