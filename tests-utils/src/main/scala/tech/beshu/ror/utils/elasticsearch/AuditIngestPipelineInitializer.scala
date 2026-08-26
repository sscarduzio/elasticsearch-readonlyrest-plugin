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
import tech.beshu.ror.utils.httpclient.RestClient

// Creates an ingest pipeline that stamps `pipeline_applied: true` on every document that passes
// through it. Used to assert (from outside ES) that audit sinks actually forward the configured
// `pipeline` name to the ES index/bulk request, rather than just threading the value through config.
final class AuditIngestPipelineInitializer(pipelineName: String) extends ElasticsearchNodeDataInitializer {

  override def initialize(esVersion: String, adminRestClient: RestClient): Unit = {
    val pipelineManager = new IngestPipelineManager(adminRestClient, esVersion)
    pipelineManager
      .putPipeline(
        pipelineName,
        ujson.read {
          """
            |{
            |  "processors": [
            |    { "set": { "field": "pipeline_applied", "value": true } }
            |  ]
            |}""".stripMargin
        }
      )
      .force()
  }

}
