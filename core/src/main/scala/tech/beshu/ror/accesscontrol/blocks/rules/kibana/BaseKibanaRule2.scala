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

import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RegularRule
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaAccessPermissions.{ActionCategory, RequestClassifier}
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaActionMatchers.*
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.KibanaAccess.*
import tech.beshu.ror.accesscontrol.domain.KibanaIndexName.*
import tech.beshu.ror.utils.RequestIdAwareLogging

// Permission table (each index in the request is classified independently, all must be permitted):
//
// Access level       | Actions                                                                                     |
//                    | ROR admin | Cluster mgmt | Involving indices                                  | Other known |
//                    |           |              | kibana indices | reporting indices | data indices  |             |
// admin              | full      | read-only    | full           | full              | read-only     | read-only   |
// rw                 | none      | read-only    | full           | full              | read-only     | read-only   |
// ro                 | none      | none         | full           | full              | read-only     | read-only   |
// ro_strict/api_only | none      | none         | read-only      | none              | read-only     | read-only   |
abstract class BaseKibanaRule2(val settings: BaseKibanaRule.Settings)
  extends RegularRule with KibanaRelatedRule with RequestIdAwareLogging {

  protected def shouldMatch(bc: BlockContext, kibanaIndex: KibanaIndexName): Boolean = {
    given BlockContext = bc
    val action = RequestClassifier.classifyAction(bc)
    val result = settings.access match {
      case Unrestricted => true
      case Admin => matchesForAdmin(action, kibanaIndex)
      case RW => matchesForRW(action, kibanaIndex)
      case RO => matchesForRO(action, kibanaIndex)
      case ROStrict | ApiOnly => matchesForROStrict(action, kibanaIndex)
    }
    logger.debug(s"Access: ${settings.access}, action: $action, result: $result")
    result
  }

  //                    | ROR admin | Cluster mgmt | kibana idx | reporting | data idx | Other known |
  // admin              | full      | read-only    | full       | full      | ro       | ro          |
  private def matchesForAdmin(action: ActionCategory, kibanaIndex: KibanaIndexName): Boolean = action match {
    case ActionCategory.RorAdmin => true
    case ActionCategory.ClusterManagement.Read => true
    case ActionCategory.ClusterManagement.Write => false
    case ncm: ActionCategory.NonClusterManagement =>
      allIndicesPermitted(ncm, kibanaIndex, kibanaFull = true, sampleDataFull = true, reportingFull = true, rorSettings = true)
    case ActionCategory.Other => false
  }

  //                    | ROR admin | Cluster mgmt | kibana idx | reporting | data idx | Other known |
  // rw                 | none      | read-only    | full       | full      | ro       | ro          |
  private def matchesForRW(action: ActionCategory, kibanaIndex: KibanaIndexName): Boolean = action match {
    case ActionCategory.RorAdmin => false
    case ActionCategory.ClusterManagement.Read => true
    case ActionCategory.ClusterManagement.Write => false
    case ncm: ActionCategory.NonClusterManagement =>
      allIndicesPermitted(ncm, kibanaIndex, kibanaFull = true, sampleDataFull = true, reportingFull = true, rorSettings = false)
    case ActionCategory.Other => false
  }

  //                    | ROR admin | Cluster mgmt | kibana idx | reporting | data idx | Other known |
  // ro                 | none      | none         | full       | full      | ro       | ro          |
  private def matchesForRO(action: ActionCategory, kibanaIndex: KibanaIndexName): Boolean = action match {
    case ActionCategory.RorAdmin => false
    case _: ActionCategory.ClusterManagement => false
    case ncm: ActionCategory.NonClusterManagement =>
      allIndicesPermitted(ncm, kibanaIndex, kibanaFull = true, sampleDataFull = false, reportingFull = true, rorSettings = false)
    case ActionCategory.Other => false
  }

  //                    | ROR admin | Cluster mgmt | kibana idx | reporting | data idx | Other known |
  // ro_strict/api_only | none      | none         | ro         | none      | ro       | ro          |
  private def matchesForROStrict(action: ActionCategory, kibanaIndex: KibanaIndexName): Boolean = action match {
    case ActionCategory.RorAdmin => false
    case _: ActionCategory.ClusterManagement => false
    case ncm: ActionCategory.NonClusterManagement =>
      allIndicesPermitted(ncm, kibanaIndex, kibanaFull = false, sampleDataFull = false, reportingFull = false, rorSettings = false)
    case ActionCategory.Other => false
  }

  private def allIndicesPermitted(ncm: ActionCategory.NonClusterManagement,
                                  kibanaIndex: KibanaIndexName,
                                  kibanaFull: Boolean,
                                  sampleDataFull: Boolean,
                                  reportingFull: Boolean,
                                  rorSettings: Boolean): Boolean = {
    val (indices, dataStreams, isRead) = ncm match {
      case ActionCategory.NonClusterManagement.Read(idx, ds) => (idx, ds, true)
      case ActionCategory.NonClusterManagement.Write(idx, ds) => (idx, ds, false)
    }
    if (indices.isEmpty && dataStreams.isEmpty)
      return isRead // no indices: treat as "other known" — read-only

    indices.forall(i => isIndexPermitted(i.name, kibanaIndex, isRead, kibanaFull, sampleDataFull, reportingFull, rorSettings)) &&
      dataStreams.forall(ds => isDataStreamPermitted(ds, isRead, sampleDataFull))
  }

  private def isIndexPermitted(indexName: ClusterIndexName,
                               kibanaIndex: KibanaIndexName,
                               isRead: Boolean,
                               kibanaFull: Boolean,
                               sampleDataFull: Boolean,
                               reportingFull: Boolean,
                               rorSettings: Boolean): Boolean = {
    if (indexName == devNullKibana.underlying) true
    else if (indexName == settings.rorIndex.toLocal) rorSettings
    else if (isReportingIndex(indexName, kibanaIndex)) reportingFull
    else if (kibanaSampleDataIndexMatcher.`match`(indexName)) sampleDataFull || isRead
    else if (indexName.isRelatedToKibanaIndex(kibanaIndex)) kibanaFull || isRead
    else isRead // data index: read-only
  }

  private def isDataStreamPermitted(ds: DataStreamName, isRead: Boolean, sampleDataFull: Boolean): Boolean = {
    if (kibanaSampleDataStreamMatcher.`match`(ds)) sampleDataFull || isRead
    else isRead // data stream: read-only
  }

  private def isReportingIndex(indexName: ClusterIndexName, kibanaIndex: KibanaIndexName): Boolean = {
    val kibanaStr = kibanaIndex.stringify
    val indexStr = indexName.stringify
    indexStr.startsWith(s".kibana-reporting-$kibanaStr") ||
      indexStr.startsWith(s".ds-.kibana-reporting-$kibanaStr")
  }
}
