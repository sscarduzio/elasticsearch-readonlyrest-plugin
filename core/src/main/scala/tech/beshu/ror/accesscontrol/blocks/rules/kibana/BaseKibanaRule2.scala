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
    val result = settings.access match {
      case Unrestricted => true
      case Admin        => matchesForAdmin(bc, kibanaIndex)
      case RW           => matchesForRW(bc, kibanaIndex)
      case RO           => matchesForRO(bc, kibanaIndex)
      case ROStrict     => matchesForROStrict(bc, kibanaIndex)
      case ApiOnly      => matchesForROStrict(bc, kibanaIndex)
    }
    logger.debug(s"Access: ${settings.access}, result: $result")
    result
  }

  //                    | kibana_index | data indices | reporting | cluster mgmt | ROR settings |
  // admin              | full         | read-only    | full      | full         | full         |
  private def matchesForAdmin(bc: BlockContext, kibanaIndex: KibanaIndexName): Boolean = {
    val (resources, action) = classify(bc, kibanaIndex)
    resources.forall {
      case RC.UserMetadataRequest | RC.DevNullKibana => isRecognized(action)
      case RC.KibanaIndex | RC.SampleData            => isRecognized(action)
      case RC.ReportingIndex                         => isRecognized(action)
      case RC.RorSettingsIndex                       => isRecognized(action)
      case RC.NoIndices                              => isRecognized(action)
      case RC.DataIndex if hasAdminPath(bc)          => isRecognized(action)
      case RC.DataIndex                              => isReadOnly(action)
    }
  }

  //                    | kibana_index | data indices | reporting | cluster mgmt | ROR settings |
  // rw                 | full         | read-only    | full      | full         | none         |
  private def matchesForRW(bc: BlockContext, kibanaIndex: KibanaIndexName): Boolean = {
    val (resources, action) = classify(bc, kibanaIndex)
    resources.forall {
      case RC.UserMetadataRequest | RC.DevNullKibana => isRecognized(action)
      case RC.KibanaIndex | RC.SampleData            => isRecognized(action)
      case RC.ReportingIndex                         => isRecognized(action)
      case RC.NoIndices                              => isRecognized(action)
      case RC.RorSettingsIndex                       => false
      case RC.DataIndex                              => isReadOnly(action)
    }
  }

  //                    | kibana_index      | data indices | reporting | cluster mgmt | ROR settings |
  // ro                 | read-only*        | read-only    | full      | read-only    | none         |
  // * non-strict writes allowed (UI state saves like short URLs, index patterns)
  private def matchesForRO(bc: BlockContext, kibanaIndex: KibanaIndexName): Boolean = {
    val (resources, action) = classify(bc, kibanaIndex)
    resources.forall {
      case RC.UserMetadataRequest | RC.DevNullKibana => isRecognized(action)
      case RC.KibanaIndex                            => isReadOnly(action) || isNonStrictWrite(action, bc, kibanaIndex)
      case RC.SampleData                             => isReadOnly(action)
      case RC.ReportingIndex                         => isRecognized(action)
      case RC.NoIndices                              => isReadOnly(action)
      case RC.RorSettingsIndex                       => false
      case RC.DataIndex                              => isReadOnly(action)
    }
  }

  //                    | kibana_index | data indices | reporting | cluster mgmt | ROR settings |
  // ro_strict/api_only | read-only    | read-only    | none      | read-only    | none         |
  private def matchesForROStrict(bc: BlockContext, kibanaIndex: KibanaIndexName): Boolean = {
    val (resources, action) = classify(bc, kibanaIndex)
    resources.forall {
      case RC.UserMetadataRequest | RC.DevNullKibana => isRecognized(action)
      case RC.KibanaIndex | RC.SampleData            => isReadOnly(action)
      case RC.ReportingIndex                         => false
      case RC.NoIndices                              => isReadOnly(action)
      case RC.RorSettingsIndex                       => false
      case RC.DataIndex                              => isReadOnly(action)
    }
  }

  private def classify(bc: BlockContext, kibanaIndex: KibanaIndexName): (Set[ResourceCategory], ActionCategory) =
    (
      RequestClassifier.classifyResources(bc, kibanaIndex, settings.rorIndex),
      RequestClassifier.classifyAction(bc)
    )

  private def isRecognized(action: ActionCategory): Boolean =
    action != AC.Other

  private def isReadOnly(action: ActionCategory): Boolean =
    action == AC.ReadOnly || action == AC.Cluster

  private def isNonStrictWrite(action: ActionCategory, bc: BlockContext, kibanaIndex: KibanaIndexName): Boolean =
    (action == AC.ReadWrite || action == AC.IndicesWrite) &&
      RequestClassifier.isNonStrictEligible(bc, kibanaIndex)

  private def hasAdminPath(bc: BlockContext): Boolean =
    RequestClassifier.hasAdminHeaderPath(bc, "index_management") ||
      RequestClassifier.hasAdminHeaderPath(bc, "tags")
}
