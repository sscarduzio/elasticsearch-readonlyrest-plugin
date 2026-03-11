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

import tech.beshu.ror.accesscontrol.audit.ecs.EcsV1AuditLogSerializer
import tech.beshu.ror.audit.AuditLogSerializer

sealed trait AuditIndexSchema

object AuditIndexSchema {
  case object RorDefault extends AuditIndexSchema

  case object EcsV1 extends AuditIndexSchema

  case object Custom extends AuditIndexSchema

  def from(serializer: AuditLogSerializer): AuditIndexSchema = serializer match {
    case serializer: EcsV1AuditLogSerializer =>
      AuditIndexSchema.EcsV1
    case serializer if serializer.getClass.getName.startsWith("tech.beshu.ror.audit.instances") =>
      AuditIndexSchema.RorDefault
    case serializer if serializer.getClass.getName.startsWith("tech.beshu.ror.requestcontext") =>
      AuditIndexSchema.RorDefault
    case other =>
      AuditIndexSchema.Custom
  }
}
