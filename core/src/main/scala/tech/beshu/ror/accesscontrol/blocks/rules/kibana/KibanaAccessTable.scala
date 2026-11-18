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
package tech.beshu.ror.accesscontrol.blocks.rules.kibana

import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaRequest.Target
import tech.beshu.ror.accesscontrol.domain.KibanaAccess
import tech.beshu.ror.accesscontrol.domain.KibanaAccess.*

// The access levels of the kibana rule, as the table in the docs: one row per access level, one column per target of
// the request (see `KibanaRequest.Target`). A request is allowed when the row "all levels" allows it, when its row
// allows it for all targets, or when the cell of its target allows it. An empty cell adds nothing.
//
// | level            | all targets                                           | Kibana indices          | data indices | no indices                 |
// |------------------|-------------------------------------------------------|-------------------------|--------------|----------------------------|
// | (all levels)     | user metadata; cluster actions;                       |                         |              |                            |
// |                  | all actions when the first index is `.kibana-devnull` |                         |              |                            |
// | `ro_strict`      |                                                       | read                    | read         | read                       |
// | `ro`, `api_only` |                                                       | read, non-strict writes | read         | read                       |
// | `rw`             | all actions on one sample data index or data stream   | read, rw, data write    | read         | read, rw                   |
// | `admin`          | as `rw`                                               | as `rw`; (A)            | read; (A)    | read, rw, admin actions    |
// | `unrestricted`   | all actions                                           |                         |              |                            |
//
// (A) admin actions from the Kibana "Index Management" and "Tags" apps, or when the first index is the ROR settings
// index.
private[kibana] object KibanaAccessTable {

  def isAllowed(access: KibanaAccess, request: KibanaRequest): Boolean =
    isAllowedForAllLevels(request) || isAllowedForLevel(access, request)

  private def isAllowedForAllLevels(request: KibanaRequest): Boolean =
    request.isUserMetadataRequest ||
      request.isFirstIndexKibanaDevNull ||
      request.isClusterAction

  private def isAllowedForLevel(access: KibanaAccess, request: KibanaRequest): Boolean = access match {
    case ROStrict     => isAllowedForRoStrict(request)
    case RO | ApiOnly => isAllowedForRo(request)
    case RW           => isAllowedForRw(request)
    case Admin        => isAllowedForAdmin(request)
    case Unrestricted => isAllowedForUnrestricted(request)
  }

  private def isAllowedForRoStrict(request: KibanaRequest): Boolean = request.target match {
    case Target.KibanaIndices => request.isReadAction
    case Target.DataIndices   => request.isReadAction
    case Target.NoIndices     => request.isReadAction
  }

  // On the Kibana indices also the writes of the Kibana UI that the read only levels need (non-strict writes)
  private def isAllowedForRo(request: KibanaRequest): Boolean = request.target match {
    case Target.KibanaIndices => request.isReadAction || (request.isNonStrictPath && request.isNonStrictAction)
    case Target.DataIndices   => request.isReadAction
    case Target.NoIndices     => request.isReadAction
  }

  private def isAllowedForRw(request: KibanaRequest): Boolean =
    request.isOneKibanaSampleDataIndexOrDataStream || (request.target match {
      case Target.KibanaIndices => request.isReadAction || request.isRwAction || request.isDataWriteAction
      case Target.DataIndices   => request.isReadAction
      case Target.NoIndices     => request.isReadAction || request.isRwAction
    })

  // `admin` is `rw`, and also the admin actions in these cases
  private def isAllowedForAdmin(request: KibanaRequest): Boolean =
    isAllowedForRw(request) || (request.isAdminAction && (request.target match {
      case Target.NoIndices =>
        true
      case Target.KibanaIndices | Target.DataIndices =>
        request.isFirstIndexRorSettingsIndex || request.isFromKibanaIndexManagementOrTags
    }))

  private def isAllowedForUnrestricted(request: KibanaRequest): Boolean = request.target match {
    case Target.KibanaIndices | Target.DataIndices | Target.NoIndices => true
  }

}
