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
package tech.beshu.ror.accesscontrol.matchers

import tech.beshu.ror.accesscontrol.domain.Action
import tech.beshu.ror.accesscontrol.domain.Action.RorAction

object ActionMatchers {

  val readNonClusterManagementActionPatternsMatcher: PatternsMatcher[Action] = PatternsMatcher.create {
    Set(
      "indices:admin/*/explain",
      "indices:admin/*/get",
      "indices:admin/aliases/exists",
      "indices:admin/aliases/get",
      "indices:admin/analyze",
      "indices:admin/exists*",
      "indices:admin/get*",
      "indices:admin/index_template/simulate",
      "indices:admin/index_template/simulate_index",
      "indices:admin/mappings/fields/get*",
      "indices:admin/mappings/get*",
      "indices:admin/migration/reindex_status",
      "indices:admin/refresh*",
      "indices:admin/search/search_shards",
      "indices:admin/template/get*",
      "indices:admin/types/exists",
      "indices:admin/validate/*",
      "indices:data/read/*",
      "indices:admin/index_template/get",
      "indices:admin/resolve/*",
      "indices:admin/xpack/rollup/search",
      "indices:monitor/*",
    ).map(Action.apply)
  }

  val readClusterManagementMatcher: PatternsMatcher[Action] = PatternsMatcher.create {
    Set(
      "cluster:monitor/*",
      "cluster:*get*",
      "cluster:admin/*/get",
      "cluster:admin/*/status",
      "cluster:admin/*/verify",
      "cluster:admin/idp/saml/metadata",
      "cluster:admin/ingest/pipeline/simulate",
      "cluster:admin/slm/stats",
      "cluster:admin/transform/node_stats",
      "cluster:admin/transform/preview",
      "cluster:admin/xpack/application/*/get",
      "cluster:admin/xpack/application/search_application/list",
      "cluster:admin/xpack/application/search_application/render_query",
      "cluster:admin/xpack/connector/list",
      "cluster:admin/xpack/connector/sync_job/list",
      "cluster:admin/xpack/deprecation/info",
      "cluster:admin/xpack/deprecation/nodes/info",
      "cluster:admin/xpack/license/basic_status",
      "cluster:admin/xpack/license/trial_status",
      "cluster:admin/xpack/ml/data_frame/analytics/explain",
      "cluster:admin/xpack/ml/data_frame/analytics/preview",
      "cluster:admin/xpack/ml/datafeeds/preview",
      "cluster:admin/xpack/query_rules/list",
      "cluster:admin/xpack/security/api_key/query",
      "cluster:admin/xpack/security/user/list_privileges",
      "cluster:admin/xpack/searchable_snapshots/cache/stats",
      "cluster:admin/xpack/security/*/get",
      "cluster:admin/xpack/security/*/query",
      "cluster:admin/xpack/*/resolve",
      "cluster:monitor/async_search/status",
    ).map(Action.apply)
  }

  val readActionPatternsMatcher: PatternsMatcher[Action] = PatternsMatcher.from(
    readNonClusterManagementActionPatternsMatcher, readClusterManagementMatcher
  )

  val writeNonClusterManagementActionPatternsMatcher: PatternsMatcher[Action] = PatternsMatcher.create {
    RorAction.writeActions ++
      Set(
        "indices:admin/create",
        "indices:admin/create_index",
        "indices:admin/mapping/put",
        "indices:data/write/*",
        "indices:admin/template/*",
        "indices:admin/aliases/*",
        "indices:admin/data_stream/*",
        "indices:admin/index_template/*",
      ).map(Action.apply)
  }

  val writeClusterManagementMatcher: PatternsMatcher[Action] = PatternsMatcher.create {
    RorAction.writeActions ++
      Set(
        "cluster:admin/settings/*",
        "cluster:admin/component_template/*",
      ).map(Action.apply)
  }

  val writeActionPatternsMatcher: PatternsMatcher[Action] = PatternsMatcher.from(
    writeNonClusterManagementActionPatternsMatcher, writeClusterManagementMatcher
  )

  val otherKnownReadActionPatternsMatcher: PatternsMatcher[Action] = PatternsMatcher.create {
    Set(
      "cluster:admin/*/validate",
      "cluster:admin/*/validate/*",
      "cluster:admin/*/prevalidate_*",
      "cluster:admin/*/estimate_*",
      "cluster:admin/*/prepare",
      "cluster:admin/xpack/security/*/has_privileges",
      "cluster:admin/xpack/security/*/suggest",
      "cluster:admin/xpack/security/*/authenticate",
    ).map(Action.apply)
  }

  val otherKnownWriteActionPatternsMatcher: PatternsMatcher[Action] = PatternsMatcher.create {
    Set(
      "cluster:admin/*",
      "indices:admin/*",
    ).map(Action.apply)
  }

}
