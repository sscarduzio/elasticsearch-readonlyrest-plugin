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

import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditOutputConfig.*
import tech.beshu.ror.accesscontrol.audit.output.{
  DataStreamBasedAuditOutputServiceCreator,
  IndexBasedAuditOutputServiceCreator
}

sealed trait EsAuditCapabilities

object EsAuditCapabilities {

  /**
   * Audit outputs that all supported ES versions accept. ES older than
   * [[tech.beshu.ror.constants.EsFeatureVersions.dataStreamSupport]] has no data streams.
   */
  type SupportedByAllEsVersions = EsIndexBased | LogBased | RollingFileBased | Disabled.type

  final class IndexOnly(val creator: IndexBasedAuditOutputServiceCreator) extends EsAuditCapabilities

  final class IndexOrDataStream(
      val indexCreator: IndexBasedAuditOutputServiceCreator,
      val dataStreamCreator: DataStreamBasedAuditOutputServiceCreator
  ) extends EsAuditCapabilities

}
