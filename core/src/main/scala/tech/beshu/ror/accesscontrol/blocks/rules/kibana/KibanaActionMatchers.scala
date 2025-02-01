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

import tech.beshu.ror.accesscontrol.domain.Action.RorAction
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Local
import tech.beshu.ror.accesscontrol.domain.{Action, ClusterIndexName, DataStreamName, IndexName}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.utils.RefinedUtils.*

object KibanaActionMatchers {

  val roActionPatternsMatcher: PatternsMatcher[Action] = PatternsMatcher.create {
    Set(
      "indices:admin/exists",
      "indices:admin/mappings/fields/get*",
      "indices:admin/mappings/get*",
      "indices:admin/validate/query",
      "indices:admin/get",
      "indices:admin/refresh*",
      "indices:data/read/*",
      "indices:admin/resolve/*",
      "indices:admin/aliases/get",
      "indices:admin/data_stream/get",
      "cluster:admin/component_template/get",
      "indices:admin/index_template/get",
      "indices:admin/*/explain",
      "indices:data/read/xpack/rollup/get/*",
      "indices:monitor/*"
    ).map(Action.apply)
  }

  val rwActionPatternsMatcher: PatternsMatcher[Action] = PatternsMatcher.create {
    RorAction.writeActions ++
      Set(
        "indices:admin/create",
        "indices:admin/create_index",
        "indices:admin/mapping/put",
        "indices:data/write/delete*",
        "indices:data/write/index",
        "indices:data/write/update*",
        "indices:data/write/bulk*",
        "indices:admin/template/*",
        "cluster:admin/settings/*",
        "indices:admin/aliases/*",
        "indices:admin/data_stream/*",
        "indices:admin/index_template/*",
        "cluster:admin/component_template/*",
        "cluster:admin/migration/get_system_feature",
      ).map(Action.apply)
  }

  val clusterActionPatternsMatcher: PatternsMatcher[Action] = PatternsMatcher.create {
    Set(
      "cluster:monitor/*",
      "cluster:*/xpack/*",
      "indices:admin/template/get*",
      "cluster:*/info",
      "cluster:*/get"
    ).map(Action.apply)
  }

  val adminActionPatternsMatcher: PatternsMatcher[Action] = PatternsMatcher.create {
    RorAction.adminActions ++
      Set(
        "cluster:internal_ror/*",
        "indices:data/write/*", // <-- DEPRECATED!
        "indices:admin/create",
        "indices:admin/create_index",
        "indices:admin/ilm/*",
        "cluster:admin/ingest/pipeline/put",
        "cluster:admin/ingest/pipeline/delete",
        "indices:admin/data_stream/get",
        "indices:admin/settings/update",
        "cluster:admin/ilm/put",
        "cluster:admin/ilm/delete",
        "cluster:admin/repository/put",
        "cluster:admin/repository/delete",
        "indices:data/write/update/byquery",
        "indices:admin/data_stream/delete",
        "cluster:admin/component_template/get",
        "cluster:admin/component_template/delete",
        "cluster:admin/component_template/put",
        "indices:admin/delete",
        "indices:admin/index_template/get",
        "indices:admin/index_template/delete",
        "indices:admin/index_template/put",
        "indices:admin/index_template/simulate",
        "cluster:admin/slm/put",
        "cluster:admin/slm/execute",
        "cluster:admin/slm/delete"
      ).map(Action.apply)
  }

  val nonStrictActions: PatternsMatcher[Action] = PatternsMatcher.create(Set(
    Action("indices:data/write/*"), Action("indices:admin/template/put")
  ))

  val indicesWriteAction: PatternsMatcher[Action] = PatternsMatcher.create(Set(Action("indices:data/write/*")))

  val kibanaSampleDataIndexMatcher: PatternsMatcher[ClusterIndexName] = PatternsMatcher.create(
    Set(Local(IndexName.Pattern.unsafeFromNes(nes("kibana_sample_data_*"))))
  )
  val kibanaSampleDataStreamMatcher: PatternsMatcher[DataStreamName] = PatternsMatcher.create(
    Set(DataStreamName.Pattern.fromNes(nes("kibana_sample_data_*")))
  )
}
