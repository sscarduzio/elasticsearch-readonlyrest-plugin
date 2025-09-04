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
 * Serializer for audit events that is aware of **rule-defined verbosity**.
 *
 * - Includes `CommonFields` and `EsEnvironmentFields`.
 * - Serializes all non-Allowed events.
 * - Serializes `Allowed` events only if the corresponding rule
 * specifies that they should be logged at `Verbosity.Info`.
 *
 * This is the recommended serializer for standard audit logging.
 */
class BlockVerbosityAwareAuditLogSerializer extends DefaultAuditLogSerializer

/**
 * Base implementation delegating to [[DefaultAuditLogSerializerV2]].
 *
 * - Not intended for direct external use.
 * - Provides the underlying logic for [[BlockVerbosityAwareAuditLogSerializer]].
 */
class DefaultAuditLogSerializer extends DefaultAuditLogSerializerV2
