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
// Access level       | Actions                                                                                     |
//                    | ROR admin | Cluster mgmt | Involving indices                                  | Other known |
//                    |           |              | kibana indices | reporting indices | data indices  |             |
// admin              | full      | read-only    | full           | full              | read-only     | read-only   |
// rw                 | none      | read-only    | full           | full              | read-only     | read-only   |
// ro                 | none      | none         | full           | full              | read-only     | read-only   |
// ro_strict/api_only | none      | none         | read-only      | none              | read-only     | read-only   | 
abstract class BaseKibanaRule2(val settings: BaseKibanaRule.Settings)
  extends RegularRule with KibanaRelatedRule with RequestIdAwareLogging {

  import ActionCategory as AC

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

  private def matchesForAdmin(bc: BlockContext, kibanaIndex: KibanaIndexName, action: ActionCategory): Boolean = {
    val resources = classifyResources(bc, kibanaIndex)
    resources.forall {
      case _: ResourceCategory.KibanaRelatedResource => true
      case ResourceCategory.DataIndex => isReadOnly(action)
      case ResourceCategory.NonIndexResource => isReadOnly(action)
      case ResourceCategory.RorSettingsIndex => true
    }
  }

  private def matchesForRW(bc: BlockContext, kibanaIndex: KibanaIndexName, action: ActionCategory): Boolean = {
    val resources = classifyResources(bc, kibanaIndex)
    resources.forall {
      case ResourceCategory.DevNullKibana => true
      // indices
      case ResourceCategory.KibanaIndex => true
      case ResourceCategory.ReportingIndex => true
      case ResourceCategory.SampleData => true
      case ResourceCategory.DataIndex => isReadOnly(action)
      // cluster mgmt
      case ResourceCategory.NonIndexResource => isReadOnly(action)
      // ROR settings
      case ResourceCategory.RorSettingsIndex => false
    }
  }

  private def matchesForRO(bc: BlockContext, kibanaIndex: KibanaIndexName, action: ActionCategory): Boolean = {
    val resources = classifyResources(bc, kibanaIndex)
    resources.forall {
      case ResourceCategory.DevNullKibana => true
      // indices
      case ResourceCategory.KibanaIndex => true
      case ResourceCategory.ReportingIndex => true
      case ResourceCategory.SampleData => isReadOnly(action)
      case ResourceCategory.DataIndex => isReadOnly(action)
      // cluster: allow monitoring reads, block management actions
      case ResourceCategory.NonIndexResource => isReadOnly(action) && !isClusterManagement(bc)
      // ROR settings
      case ResourceCategory.RorSettingsIndex => false
    }
  }

  private def matchesForROStrict(bc: BlockContext, kibanaIndex: KibanaIndexName, action: ActionCategory): Boolean = {
    val resources = classifyResources(bc, kibanaIndex)
    resources.forall {
      case ResourceCategory.DevNullKibana => true
      // indices
      case ResourceCategory.KibanaIndex => isReadOnly(action)
      case ResourceCategory.ReportingIndex => false
      case ResourceCategory.SampleData => isReadOnly(action)
      case ResourceCategory.DataIndex => isReadOnly(action)
      // cluster: allow monitoring reads, block management actions
      case ResourceCategory.NonIndexResource => isReadOnly(action) && !isClusterManagement(bc)
      // ROR settings
      case ResourceCategory.RorSettingsIndex => false
    }
  }

  private def classifyResources(bc: BlockContext, kibanaIndex: KibanaIndexName): Set[ResourceCategory] =
    RequestClassifier.classifyResources(bc, kibanaIndex, settings.rorIndex)

  private def isReadOnly(action: ActionCategory): Boolean =
    action == AC.ReadOnly

  private def isClusterManagement(bc: BlockContext): Boolean =
    RequestClassifier.isClusterManagementAction(bc)
}
