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
package tech.beshu.ror.accesscontrol.blocks.metadata

import tech.beshu.ror.accesscontrol.domain.Json.JsonRepresentation
import tech.beshu.ror.accesscontrol.domain.{KibanaAccess, KibanaAllowedApiPath, KibanaApp, KibanaIndexName}
import tech.beshu.ror.syntax.Set

final case class KibanaMetadata(access: KibanaAccess,
                                index: Option[KibanaIndexName],
                                templateIndex: Option[KibanaIndexName],
                                hiddenApps: Set[KibanaApp],
                                allowedApiPaths: Set[KibanaAllowedApiPath],
                                genericMetadata: Option[JsonRepresentation])
object KibanaMetadata {
  def default: KibanaMetadata = KibanaMetadata(
    access = KibanaAccess.Unrestricted,
    index = None,
    templateIndex = None,
    hiddenApps = Set.empty,
    allowedApiPaths = Set.empty,
    genericMetadata = None
  )
}
