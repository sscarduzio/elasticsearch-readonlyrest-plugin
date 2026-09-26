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
package tech.beshu.ror.utils.elasticsearch

import tech.beshu.ror.utils.TestUjson.ujson
import tech.beshu.ror.utils.containers.ElasticsearchNodeDataInitializer
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.httpclient.RestClient

final class AuditIngestPipelineInitializer(pipelineName: String, marker: String)
    extends ElasticsearchNodeDataInitializer {

  override def initialize(esVersion: String, adminRestClient: RestClient): Unit = {
    new IngestPipelineManager(adminRestClient, esVersion)
      .putPipeline(pipelineName, AuditIngestPipelineInitializer.markerPipeline(marker))
      .force()
  }

}

object AuditIngestPipelineInitializer {

  val markerField = "pipeline_marker"

  def markerPipeline(marker: String): JSON = ujson.read {
    s"""
       |{
       |  "processors": [
       |    { "set": { "field": "$markerField", "value": "$marker" } }
       |  ]
       |}""".stripMargin
  }

}
