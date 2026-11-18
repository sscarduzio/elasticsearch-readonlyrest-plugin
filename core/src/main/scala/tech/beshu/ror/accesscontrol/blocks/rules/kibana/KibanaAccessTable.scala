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
// the request (see `KibanaRequest.Target`). All levels also allow the user metadata request, the cluster actions, and
// all actions when the first index is `.kibana-devnull`.
//
// | level            | Kibana indices          | data indices | Kibana sample data | no indices              |
// |------------------|-------------------------|--------------|--------------------|-------------------------|
// | `ro_strict`      | read                    | read         | read               | read                    |
// | `ro`, `api_only` | read, non-strict writes | read         | read               | read                    |
// | `rw`             | read, rw                | read         | all actions        | read, rw                |
// | `admin`          | read, rw, (AAA)         | read, (AAA)  | all actions        | read, rw, admin actions |
// | `unrestricted`   | all actions             | all actions  | all actions        | all actions             |
//
// (AAA) additional admin actions from the Kibana "Index Management" and "Tags" apps, or when the first index is the
// ROR settings index
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
    case Target.KibanaIndices    => request.isReadAction
    case Target.DataIndices      => request.isReadAction
    case Target.KibanaSampleData => request.isReadAction
    case Target.NoIndices        => request.isReadAction
  }

  private def isAllowedForRo(request: KibanaRequest): Boolean = request.target match {
    case Target.KibanaIndices    => request.isReadAction || request.isNonStrictWrite
    case Target.DataIndices      => request.isReadAction
    case Target.KibanaSampleData => request.isReadAction
    case Target.NoIndices        => request.isReadAction
  }

  private def isAllowedForRw(request: KibanaRequest): Boolean = request.target match {
    case Target.KibanaIndices    => request.isReadAction || request.isRwAction
    case Target.DataIndices      => request.isReadAction
    case Target.KibanaSampleData => true
    case Target.NoIndices        => request.isReadAction || request.isRwAction
  }

  private def isAllowedForAdmin(request: KibanaRequest): Boolean = request.target match {
    case Target.KibanaIndices =>
      request.isReadAction ||
      request.isRwAction ||
      isAdminActionFromKibanaAppOrOnRorSettingsIndex(request)
    case Target.DataIndices =>
      request.isReadAction ||
      isAdminActionFromKibanaAppOrOnRorSettingsIndex(request)
    case Target.KibanaSampleData =>
      true
    case Target.NoIndices =>
      request.isReadAction ||
      request.isRwAction ||
      request.isAdminAction
  }

  private def isAllowedForUnrestricted(request: KibanaRequest): Boolean = request.target match {
    case Target.KibanaIndices    => true
    case Target.DataIndices      => true
    case Target.KibanaSampleData => true
    case Target.NoIndices        => true
  }

  // (A) in the table
  private def isAdminActionFromKibanaAppOrOnRorSettingsIndex(request: KibanaRequest): Boolean =
    request.isAdminAction && (request.isFromKibanaIndexManagementOrTags || request.isFirstIndexRorSettingsIndex)

}
