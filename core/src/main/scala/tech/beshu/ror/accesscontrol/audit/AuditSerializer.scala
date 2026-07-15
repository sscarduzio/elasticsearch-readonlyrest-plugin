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
package tech.beshu.ror.accesscontrol.audit

import org.json.JSONObject
import tech.beshu.ror.accesscontrol.audit.configurable.ConfigurableAuditLogSerializer
import tech.beshu.ror.accesscontrol.audit.ecs.EcsV1AuditLogSerializer
import tech.beshu.ror.audit.utils.AuditSerializationHelper.{AllowedEventMode, AuditFieldPath, AuditFieldValueDescriptor}
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}

sealed trait AuditSerializer

sealed trait JsonAuditSerializer extends AuditSerializer

sealed trait TextAuditSerializer extends AuditSerializer

object AuditSerializer {
  final case class Delegating(serializer: AuditLogSerializer) extends JsonAuditSerializer

  case object Acl extends TextAuditSerializer

  final case class EcsV1(allowedEventMode: AllowedEventMode, includeFullRequestContent: Boolean)
      extends JsonAuditSerializer

  final case class Configurable(
      allowedEventMode: AllowedEventMode,
      fields: Map[AuditFieldPath, AuditFieldValueDescriptor]
  ) extends JsonAuditSerializer

  extension (serializer: JsonAuditSerializer) {

    def toJsonObject(context: AuditResponseContext): Option[JSONObject] = serializer match {
      case Delegating(delegate) =>
        delegate.onResponse(context)
      case EcsV1(allowedEventMode, includeFullRequestContent) =>
        EcsV1AuditLogSerializer.onResponse(context, allowedEventMode, includeFullRequestContent)
      case Configurable(allowedEventMode, fields) =>
        ConfigurableAuditLogSerializer.onResponse(context, allowedEventMode, fields)
    }

  }

}
