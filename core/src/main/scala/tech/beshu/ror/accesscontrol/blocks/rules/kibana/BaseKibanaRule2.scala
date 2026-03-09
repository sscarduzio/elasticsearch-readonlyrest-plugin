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
//
// Access level       | Indices                                           | Cluster mgmt | ROR settings |
//                    | kibana indices | reporting indices | data indices |              |              |
// admin              | full           | full              | read-only    | read-only    | full         |
// rw                 | full           | full              | read-only    | read-only    | none         |
// ro                 | full           | full              | read-only    | none         | none         |
// ro_strict/api_only | read-only      | none              | read-only    | none         | none         |
abstract class BaseKibanaRule2(val settings: BaseKibanaRule.Settings)
  extends RegularRule with KibanaRelatedRule with RequestIdAwareLogging {

  import ActionCategory as AC
  import ResourceCategory as RC

  protected def shouldMatch(bc: BlockContext, kibanaIndex: KibanaIndexName): Boolean = {
    given BlockContext = bc

    val action = RequestClassifier.classifyAction(bc)
    val result = (settings.access, action) match {
      case (Unrestricted, _) => true
      case (_, AC.Other) => false
      case (Admin, _) => matchesForAdmin(bc, kibanaIndex, action)
      case (RW, _) => matchesForRW(bc, kibanaIndex, action)
      case (RO, _) => matchesForRO(bc, kibanaIndex, action)
      case (ROStrict, _) | (ApiOnly, _) => matchesForROStrict(bc, kibanaIndex, action)
    }
    logger.debug(s"Access: ${settings.access}, result: $result")
    result
  }

  //                    | kibana indices | reporting | data indices | cluster mgmt | ROR settings |
  // admin              | full           | full      | read-only    | read-only    | full         |
  private def matchesForAdmin(bc: BlockContext, kibanaIndex: KibanaIndexName, action: ActionCategory): Boolean = {
    val resources = classifyResources(bc, kibanaIndex)
    resources.forall {
      case RC.DevNullKibana                          => true
      // indices
      case RC.KibanaIndex                            => true
      case RC.ReportingIndex                         => true
      case RC.SampleData                             => true
      case RC.DataIndex                              => isReadOnly(action)
      // cluster mgmt
      case RC.NonIndexResource                              => isReadOnly(action)
      // ROR settings
      case RC.RorSettingsIndex                       => true
    }
  }

  //                    | kibana indices | reporting | data indices | cluster mgmt | ROR settings |
  // rw                 | full           | full      | read-only    | read-only    | none         |
  private def matchesForRW(bc: BlockContext, kibanaIndex: KibanaIndexName, action: ActionCategory): Boolean = {
    val resources = classifyResources(bc, kibanaIndex)
    resources.forall {
      case RC.DevNullKibana                          => true
      // indices
      case RC.KibanaIndex                            => true
      case RC.ReportingIndex                         => true
      case RC.SampleData                             => true
      case RC.DataIndex                              => isReadOnly(action)
      // cluster mgmt
      case RC.NonIndexResource                              => isReadOnly(action)
      // ROR settings
      case RC.RorSettingsIndex                       => false
    }
  }

  //                    | kibana indices | reporting | data indices | cluster mgmt | ROR settings |
  // ro                 | full           | full      | read-only    | none         | none         |
  private def matchesForRO(bc: BlockContext, kibanaIndex: KibanaIndexName, action: ActionCategory): Boolean = {
    val resources = classifyResources(bc, kibanaIndex)
    resources.forall {
      case RC.DevNullKibana                          => true
      // indices
      case RC.KibanaIndex                            => true
      case RC.ReportingIndex                         => true
      case RC.SampleData                             => isReadOnly(action)
      case RC.DataIndex                              => isReadOnly(action)
      // cluster mgmt
      case RC.NonIndexResource                              => false
      // ROR settings
      case RC.RorSettingsIndex                       => false
    }
  }

  //                    | kibana indices | reporting | data indices | cluster mgmt | ROR settings |
  // ro_strict/api_only | read-only      | none      | read-only    | none         | none         |
  private def matchesForROStrict(bc: BlockContext, kibanaIndex: KibanaIndexName, action: ActionCategory): Boolean = {
    val resources = classifyResources(bc, kibanaIndex)
    resources.forall {
      case RC.DevNullKibana                          => true
      // indices
      case RC.KibanaIndex                            => isReadOnly(action)
      case RC.ReportingIndex                         => false
      case RC.SampleData                             => isReadOnly(action)
      case RC.DataIndex                              => isReadOnly(action)
      // cluster mgmt
      case RC.NonIndexResource                              => false
      // ROR settings
      case RC.RorSettingsIndex                       => false
    }
  }

  private def classifyResources(bc: BlockContext, kibanaIndex: KibanaIndexName): Set[ResourceCategory] =
    RequestClassifier.classifyResources(bc, kibanaIndex, settings.rorIndex)

  private def isReadOnly(action: ActionCategory): Boolean =
    action == AC.ReadOnly
}
