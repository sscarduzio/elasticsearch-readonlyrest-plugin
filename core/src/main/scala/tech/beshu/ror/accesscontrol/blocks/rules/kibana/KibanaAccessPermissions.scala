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
import tech.beshu.ror.accesscontrol.blocks.BlockContext.*
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaActionMatchers.*
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.Action.RorAction
import tech.beshu.ror.accesscontrol.domain.KibanaIndexName.*
import tech.beshu.ror.accesscontrol.matchers.ActionMatchers
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*

import java.util.regex.Pattern
import scala.util.Try

object KibanaAccessPermissions {

  sealed trait ActionCategory
  object ActionCategory {

    case object RorAdmin extends ActionCategory

//    sealed trait ClusterManagement extends ActionCategory
//    object ClusterManagement {
//      case object Read extends ClusterManagement
//      case object Write extends ClusterManagement
//    }

    trait NonClusterManagement extends ActionCategory
    object NonClusterManagement {
      final case class Read(indices: Set[RequestedIndex[ClusterIndexName]],
                            dataStreams: Set[DataStreamName]) extends NonClusterManagement
      final case class Write(indices: Set[RequestedIndex[ClusterIndexName]],
                             dataStreams: Set[DataStreamName]) extends NonClusterManagement
    }

    case object Unknown extends ActionCategory
  }

  object RequestClassifier {

    def classifyAction(bc: BlockContext): ActionCategory = {
      bc.requestContext.action match {
        case _: RorAction.AdminRorAction =>
          ActionCategory.RorAdmin
        case action if ActionMatchers.readNonClusterManagementActionPatternsMatcher.`match`(action) =>
          ActionCategory.NonClusterManagement.Read(bc.indices, bc.dataStreams)
//        case action if ActionMatchers.readClusterManagementMatcher.`match`(action) =>
//          ActionCategory.ClusterManagement.Read
        case action if ActionMatchers.writeNonClusterManagementActionPatternsMatcher.`match`(action) =>
          ActionCategory.NonClusterManagement.Write(bc.indices, bc.dataStreams)
//        case action if ActionMatchers.writeClusterManagementMatcher.`match`(action) =>
//          ActionCategory.ClusterManagement.Write
        case _ =>
          ActionCategory.Unknown
      }
    }

    def isNonStrictEligible(bc: BlockContext, kibanaIndex: KibanaIndexName): Boolean = {
      val path = bc.requestContext.restRequest.path
      val action = bc.requestContext.action
      val nonStrictAllowedPaths = Try(Pattern.compile(
        "^/@kibana_index/(url|config/.*/_create|index-pattern|doc/index-pattern.*|doc/url.*)/.*|^/_template/.*|^/@kibana_index/doc/telemetry.*|^/@kibana_index/(_update/index-pattern.*|_update/url.*)|^/@kibana_index/_create/(url:.*)"
          .replace("@kibana_index", kibanaIndex.stringify)
      )).toOption
      val pathMatch = nonStrictAllowedPaths.exists(_.matcher(path.value.value).find())
      pathMatch && nonStrictActions.`match`(action)
    }

    def hasAdminHeaderPath(bc: BlockContext, pathPart: String): Boolean = {
      bc.requestContext
        .restRequest
        .allHeaders
        .find(_.name === Header.Name.kibanaRequestPath)
        .exists(_.value.value.contains(s"/$pathPart/"))
    }
  }
}
