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
// the request. All levels also allow the user metadata request.
//
// | level            | Kibana indices     | data indices | Kibana sample data | no indices   |
// |------------------|--------------------|--------------|--------------------|--------------|
// | `ro_strict`      | R                  | R            | R                  | R            |
// | `ro`, `api_only` | R, NS              | R            | R                  | R            |
// | `rw`             | R, W, CR           | R, CR        | R, SD              | R, CR        |
// | `admin`          | R, W, CR           | R, CR        | R, SD              | R, CR, ROR   |
// | `unrestricted`   | ALL                | ALL          | ALL                | ALL          |
//
// R     read (`readActions`)
// NS    non-strict writes to the main Kibana index (`isNonStrictWrite`): the "Discover" state, short URLs, data
//       views, telemetry and new config documents
// W     document writes, index and data stream creation, and mapping updates (`writeActions`). Index delete and the
//       other index management actions are cluster management writes.
// CR    cluster management read (`clusterManagementReadActions`)
// ROR   ROR settings API (`rorAdminActions`); its requests have no indices
// SD    the actions that the Kibana "Sample data" page sends to add and remove a sample data set (`sampleDataActions`)
// ALL   all actions
//
// Only `unrestricted` allows the cluster management writes.
private[ror] object KibanaAccessTable {

  sealed trait Decision {
    def isAllowed: Boolean
  }

  object Decision {

    case object AllowUserMetadataRequest extends Decision {
      override val isAllowed: Boolean = true
    }

    final case class Cell(access: KibanaAccess, target: Target, isAllowed: Boolean) extends Decision
  }

  private[kibana] def decide(access: KibanaAccess, request: KibanaRequest): Decision =
    if (request.isUserMetadataRequest) Decision.AllowUserMetadataRequest
    else Decision.Cell(access, request.target, isAllowedForLevel(access, request))

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
    case Target.KibanaIndices =>
      request.isReadAction ||
      request.isWriteAction ||
      request.isClusterManagementReadAction
    case Target.DataIndices =>
      request.isReadAction ||
      request.isClusterManagementReadAction
    case Target.KibanaSampleData =>
      request.isReadAction ||
      request.isSampleDataAction
    case Target.NoIndices =>
      request.isReadAction ||
      request.isClusterManagementReadAction
  }

  private def isAllowedForAdmin(request: KibanaRequest): Boolean = request.target match {
    case Target.KibanaIndices =>
      request.isReadAction ||
      request.isWriteAction ||
      request.isClusterManagementReadAction
    case Target.DataIndices =>
      request.isReadAction ||
      request.isClusterManagementReadAction
    case Target.KibanaSampleData =>
      request.isReadAction ||
      request.isSampleDataAction
    case Target.NoIndices =>
      request.isReadAction ||
      request.isClusterManagementReadAction ||
      request.isRorAdminAction
  }

  private def isAllowedForUnrestricted(request: KibanaRequest): Boolean = request.target match {
    case Target.KibanaIndices    => true
    case Target.DataIndices      => true
    case Target.KibanaSampleData => true
    case Target.NoIndices        => true
  }

}
