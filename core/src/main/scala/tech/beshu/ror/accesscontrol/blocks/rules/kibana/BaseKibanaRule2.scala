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
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaAccessPermissions.{ActionCategory, RequestClassifier, ResourceCategory}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.KibanaAccess.*
import tech.beshu.ror.utils.RequestIdAwareLogging

// Permission table (each index in the request is classified independently, all must be permitted):
//                    | kibana_index | data indices | reporting | cluster mgmt | ROR settings |
// admin              | full         | read-only    | full      | full         | full         |
// rw                 | full         | read-only    | full      | full         | none         |
// ro                 | read-only*   | read-only    | full      | read-only    | none         |
// ro_strict/api_only | read-only    | read-only    | none      | read-only    | none         |
// * non-strict writes allowed (UI state saves)

abstract class BaseKibanaRule2(val settings: BaseKibanaRule.Settings)
  extends RegularRule with KibanaRelatedRule with RequestIdAwareLogging {

  import ActionCategory as AC
  import ResourceCategory as RC

  protected def shouldMatch(bc: BlockContext, kibanaIndex: KibanaIndexName): Boolean = {
    given BlockContext = bc
    val action = RequestClassifier.classifyAction(bc)
    if (action == AC.Other) {
      logger.debug(s"Access: ${settings.access}, result: false (unrecognized action)")
      return false
    }
    val result = settings.access match {
      case Unrestricted => true
      case Admin        => matchesForAdmin(bc, kibanaIndex, action)
      case RW           => matchesForRW(bc, kibanaIndex, action)
      case RO           => matchesForRO(bc, kibanaIndex, action)
      case ROStrict     => matchesForROStrict(bc, kibanaIndex, action)
      case ApiOnly      => matchesForROStrict(bc, kibanaIndex, action)
    }
    logger.debug(s"Access: ${settings.access}, result: $result")
    result
  }

  //                    | kibana_index | data indices | reporting | cluster mgmt | ROR settings |
  // admin              | full         | read-only    | full      | full         | full         |
  private def matchesForAdmin(bc: BlockContext, kibanaIndex: KibanaIndexName, action: ActionCategory): Boolean = {
    val resources = classifyResources(bc, kibanaIndex)
    resources.forall {
      case RC.UserMetadataRequest | RC.DevNullKibana => true
      case RC.KibanaIndex | RC.SampleData            => true
      case RC.ReportingIndex                         => true
      case RC.RorSettingsIndex                       => true
      case RC.NoIndices                              => true
      case RC.DataIndex if hasAdminPath(bc)          => true
      case RC.DataIndex                              => isReadOnly(action)
    }
  }

  //                    | kibana_index | data indices | reporting | cluster mgmt | ROR settings |
  // rw                 | full         | read-only    | full      | full         | none         |
  private def matchesForRW(bc: BlockContext, kibanaIndex: KibanaIndexName, action: ActionCategory): Boolean = {
    val resources = classifyResources(bc, kibanaIndex)
    resources.forall {
      case RC.UserMetadataRequest | RC.DevNullKibana => true
      case RC.KibanaIndex | RC.SampleData            => true
      case RC.ReportingIndex                         => true
      case RC.NoIndices                              => true
      case RC.RorSettingsIndex                       => false
      case RC.DataIndex                              => isReadOnly(action)
    }
  }

  //                    | kibana_index      | data indices | reporting | cluster mgmt | ROR settings |
  // ro                 | read-only*        | read-only    | full      | read-only    | none         |
  // * non-strict writes allowed (UI state saves like short URLs, index patterns)
  private def matchesForRO(bc: BlockContext, kibanaIndex: KibanaIndexName, action: ActionCategory): Boolean = {
    val resources = classifyResources(bc, kibanaIndex)
    resources.forall {
      case RC.UserMetadataRequest | RC.DevNullKibana => true
      case RC.KibanaIndex                            => isReadOnly(action) || isNonStrictWrite(action, bc, kibanaIndex)
      case RC.SampleData                             => isReadOnly(action)
      case RC.ReportingIndex                         => true
      case RC.NoIndices                              => isReadOnly(action)
      case RC.RorSettingsIndex                       => false
      case RC.DataIndex                              => isReadOnly(action)
    }
  }

  //                    | kibana_index | data indices | reporting | cluster mgmt | ROR settings |
  // ro_strict/api_only | read-only    | read-only    | none      | read-only    | none         |
  private def matchesForROStrict(bc: BlockContext, kibanaIndex: KibanaIndexName, action: ActionCategory): Boolean = {
    val resources = classifyResources(bc, kibanaIndex)
    resources.forall {
      case RC.UserMetadataRequest | RC.DevNullKibana => true
      case RC.KibanaIndex | RC.SampleData            => isReadOnly(action)
      case RC.ReportingIndex                         => false
      case RC.NoIndices                              => isReadOnly(action)
      case RC.RorSettingsIndex                       => false
      case RC.DataIndex                              => isReadOnly(action)
    }
  }

  private def classifyResources(bc: BlockContext, kibanaIndex: KibanaIndexName): Set[ResourceCategory] =
    RequestClassifier.classifyResources(bc, kibanaIndex, settings.rorIndex)

  private def isReadOnly(action: ActionCategory): Boolean =
    action == AC.ReadOnly || action == AC.Cluster

  private def isNonStrictWrite(action: ActionCategory, bc: BlockContext, kibanaIndex: KibanaIndexName): Boolean =
    (action == AC.ReadWrite || action == AC.IndicesWrite) &&
      RequestClassifier.isNonStrictEligible(bc, kibanaIndex)

  private def hasAdminPath(bc: BlockContext): Boolean =
    RequestClassifier.hasAdminHeaderPath(bc, "index_management") ||
      RequestClassifier.hasAdminHeaderPath(bc, "tags")
}
