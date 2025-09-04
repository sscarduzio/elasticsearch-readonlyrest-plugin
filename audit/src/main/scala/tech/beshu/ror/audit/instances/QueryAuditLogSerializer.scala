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

/**
 * Public alias for [[QueryAuditLogSerializerV2]].
 *
 * - Captures full request content along with common and ES environment fields.
 * - Respects rule-defined verbosity for `Allowed` events:
 *   only serializes them if the corresponding rule allows logging at `Verbosity.Info`.
 *
 * Prefer this class name in configurations and client code for full-content auditing.
 */
class QueryAuditLogSerializer extends QueryAuditLogSerializerV2