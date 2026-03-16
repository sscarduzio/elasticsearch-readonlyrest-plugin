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
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.BaseKibanaRule2.ContextBasedIndices
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaAccessPermissions.{ActionCategory, RequestClassifier}
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaActionMatchers.*
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.KibanaAccess.*
import tech.beshu.ror.accesscontrol.domain.KibanaIndexName.*
import tech.beshu.ror.utils.RequestIdAwareLogging

// Permission table (each index in the request is classified independently, all must be permitted):
//
// Access level       | Actions                                                                                     |
//                    | ROR admin | Cluster mgmt | Involving indices                                  | Unknown    |
//                    |           |              | kibana indices | reporting indices | data indices  |            |
// admin              | full      | read-only    | full           | full              | read-only     | none       |
// rw                 | none      | read-only    | full           | full              | read-only     | none       |
// ro                 | none      | none         | full           | full              | read-only     | none       |
// ro_strict/api_only | none      | none         | read-only      | none              | read-only     | none       |
abstract class BaseKibanaRule2(val settings: BaseKibanaRule2.Settings)
  extends RegularRule with KibanaRelatedRule with RequestIdAwareLogging {

  protected def shouldMatch(bc: BlockContext)
                           (implicit contextBasedIndices: ContextBasedIndices): Boolean = {
    given BlockContext = bc

    val action = RequestClassifier.classifyAction(bc)
    val result = settings.access match {
      case Unrestricted => true
      case Admin => matchesForAdmin(action)
      case RW => matchesForRW(action)
      case RO => matchesForRO(action)
      case ROStrict | ApiOnly => matchesForROStrict(action)
    }
    logger.debug(s"Access: ${settings.access}, action: $action, result: $result")
    result
  }

  //                    | ROR admin | Cluster mgmt | kibana idx | reporting | data idx | Unknown |
  // admin              | full      | read-only    | full       | full      | ro       | none    |
  private def matchesForAdmin(action: ActionCategory)
                             (implicit contextBasedIndices: ContextBasedIndices): Boolean = action match {
    case ActionCategory.RorAdmin => true
//    case ActionCategory.ClusterManagement.Read => true
//    case ActionCategory.ClusterManagement.Write => false
    case actionCategory: ActionCategory.NonClusterManagement =>
      allIndicesPermitted(actionCategory, kibanaFull = true, reportingFull = true, rorSettings = true)
    case ActionCategory.Unknown => false
  }

  //                    | ROR admin | Cluster mgmt | kibana idx | reporting | data idx | Unknown |
  // rw                 | none      | read-only    | full       | full      | ro       | none    |
  private def matchesForRW(action: ActionCategory)
                          (implicit contextBasedIndices: ContextBasedIndices): Boolean = action match {
    case ActionCategory.RorAdmin => false
//    case ActionCategory.ClusterManagement.Read => true
//    case ActionCategory.ClusterManagement.Write => false
    case actionCategory: ActionCategory.NonClusterManagement =>
      allIndicesPermitted(actionCategory, kibanaFull = true, reportingFull = true, rorSettings = false)
    case ActionCategory.Unknown => false
  }

  //                    | ROR admin | Cluster mgmt | kibana idx | reporting | data idx | Unknown |
  // ro                 | none      | none         | full       | full      | ro       | none    |
  private def matchesForRO(action: ActionCategory)
                          (implicit contextBasedIndices: ContextBasedIndices): Boolean = action match {
    case ActionCategory.RorAdmin => false
//    case _: ActionCategory.ClusterManagement => false
    case actionCategory: ActionCategory.NonClusterManagement =>
      allIndicesPermitted(actionCategory, kibanaFull = true, reportingFull = true, rorSettings = false)
    case ActionCategory.Unknown => false
  }

  //                    | ROR admin | Cluster mgmt | kibana idx | reporting | data idx | Unknown |
  // ro_strict/api_only | none      | none         | ro         | none      | ro       | none    |
  private def matchesForROStrict(action: ActionCategory)
                                (implicit contextBasedIndices: ContextBasedIndices): Boolean = action match {
    case ActionCategory.RorAdmin => false
//    case _: ActionCategory.ClusterManagement => false
    case actionCategory: ActionCategory.NonClusterManagement =>
      allIndicesPermitted(actionCategory, kibanaFull = false, reportingFull = false, rorSettings = false)
    case ActionCategory.Unknown => false
  }

  private def allIndicesPermitted(actionCategory: ActionCategory.NonClusterManagement,
                                  kibanaFull: Boolean,
                                  reportingFull: Boolean,
                                  rorSettings: Boolean)
                                 (implicit contextBasedIndices: ContextBasedIndices): Boolean = {
    val (indices, dataStreams, isRead) = actionCategory match {
      case ActionCategory.NonClusterManagement.Read(idx, ds) => (idx, ds, true)
      case ActionCategory.NonClusterManagement.Write(idx, ds) => (idx, ds, false)
    }
    if (indices.isEmpty && dataStreams.isEmpty) {
      isRead // no indices: treat as "other known" — read-only
    } else {
      indices.forall { index =>
        isIndexPermitted(index.name, isRead, kibanaFull, reportingFull, rorSettings)
      } && dataStreams.forall { dataStreamName =>
        isDataStreamPermitted(dataStreamName, isRead, kibanaFull)
      }
    }
  }

  private def isIndexPermitted(indexName: ClusterIndexName,
                               isRead: Boolean,
                               kibanaRelatedFull: Boolean,
                               reportingFull: Boolean,
                               rorSettings: Boolean)
                              (implicit contextBasedIndices: ContextBasedIndices): Boolean = {
    if (indexName == contextBasedIndices.rorIndex.toLocal) rorSettings
    else if (indexName.isRelatedToReportingIndex(contextBasedIndices.kibanaIndex)) reportingFull
    else if (indexName.isRelatedToKibanaIndex(contextBasedIndices.kibanaIndex)) kibanaRelatedFull || isRead
    else if (kibanaSampleDataIndexMatcher.`match`(indexName)) kibanaRelatedFull || isRead
    else isRead // data index: read-only
  }

  private def isDataStreamPermitted(dataStreamName: DataStreamName, isRead: Boolean, kibanaRelatedFull: Boolean): Boolean = {
    if (dataStreamName.isRelatedToKibanaIndex()) kibanaRelatedFull || isRead
    else if (kibanaSampleDataStreamMatcher.`match`(dataStreamName)) kibanaRelatedFull || isRead
    else isRead // data stream: read-only
  }
}

object BaseKibanaRule2 {
  final case class ContextBasedIndices(kibanaIndex: KibanaIndexName,
                                       rorIndex: RorSettingsIndex)

  abstract class Settings(val access: KibanaAccess)

}