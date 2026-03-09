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

import java.util.regex.Pattern
import scala.util.Try

object KibanaAccessPermissions {

  sealed trait ResourceCategory
  object ResourceCategory {
    sealed trait IndexResource extends ResourceCategory
    sealed trait KibanaRelatedResource extends IndexResource
    case object KibanaIndex extends KibanaRelatedResource
    case object ReportingIndex extends KibanaRelatedResource
    case object SampleData extends KibanaRelatedResource
    case object DevNullKibana extends KibanaRelatedResource
    case object DataIndex extends IndexResource
    case object RorSettingsIndex extends IndexResource
    case object NonIndexResource extends ResourceCategory
  }

  sealed trait ActionCategory
  object ActionCategory {
    // ES read actions: indices:data/read/*, indices:admin/*/get, indices:monitor/*, cluster:monitor/*, etc.
    case object ReadOnly extends ActionCategory
    // ES write actions: indices:data/write/*, indices:admin/create, cluster:admin/settings/*, etc.
    case object ReadWrite extends ActionCategory
    // ROR internal admin actions only (cluster:internal_ror/*)
    case object RorAdmin extends ActionCategory
    case object Other extends ActionCategory
  }

  object RequestClassifier {

    def classifyResources(bc: BlockContext,
                          kibanaIndex: KibanaIndexName,
                          rorIndex: RorSettingsIndex): Set[ResourceCategory] = {
      val indices = bc.indices
      val dataStreams = bc.dataStreams

      if (indices.isEmpty && dataStreams.isEmpty)
        return Set(ResourceCategory.NonIndexResource)

      val indexCategories = indices.map(i => classifyIndex(i.name, kibanaIndex, rorIndex))
      val streamCategories = dataStreams.map(ds => classifyDataStream(ds))
      (indexCategories ++ streamCategories).toSet
    }

    def classifyAction(bc: BlockContext): ActionCategory = {
      bc.requestContext.action match {
        case _: RorAction.AdminRorAction => ActionCategory.RorAdmin
        case action if ActionMatchers.readActionPatternsMatcher.`match`(action) => ActionCategory.ReadOnly
        case action if ActionMatchers.writeActionPatternsMatcher.`match`(action) => ActionCategory.ReadWrite
        case _ => ActionCategory.Other
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

    private def classifyIndex(indexName: ClusterIndexName,
                              kibanaIndex: KibanaIndexName,
                              rorIndex: RorSettingsIndex): ResourceCategory = {
      if (indexName == devNullKibana.underlying) ResourceCategory.DevNullKibana
      else if (kibanaSampleDataIndexMatcher.`match`(indexName)) ResourceCategory.SampleData
      else if (indexName == rorIndex.toLocal) ResourceCategory.RorSettingsIndex
      else if (isReportingIndex(indexName, kibanaIndex)) ResourceCategory.ReportingIndex
      else if (indexName.isRelatedToKibanaIndex(kibanaIndex)) ResourceCategory.KibanaIndex
      else ResourceCategory.DataIndex
    }

    private def classifyDataStream(ds: DataStreamName): ResourceCategory = {
      if (kibanaSampleDataStreamMatcher.`match`(ds)) ResourceCategory.SampleData
      else ResourceCategory.DataIndex
    }

    private def isReportingIndex(indexName: ClusterIndexName, kibanaIndex: KibanaIndexName): Boolean = {
      val kibanaStr = kibanaIndex.stringify
      val indexStr = indexName.stringify
      indexStr.startsWith(s"$kibanaStr-reporting-") ||
        indexStr.startsWith(s".ds-$kibanaStr-reporting-")
    }
  }
}
