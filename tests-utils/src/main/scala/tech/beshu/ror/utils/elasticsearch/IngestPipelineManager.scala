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

import org.apache.http.client.methods.HttpPut
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.TestUjson.ujson
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.httpclient.RestClient

class IngestPipelineManager(client: RestClient, esVersion: String)
    extends BaseManager(client, esVersion, esNativeApi = true) {

  def putPipeline(name: String, body: JSON): SimpleResponse = {
    call(createPutPipelineRequest(name, body), new SimpleResponse(_))
  }

  private def createPutPipelineRequest(name: String, body: JSON): HttpPut = {
    val request = new HttpPut(client.from(s"/_ingest/pipeline/$name"))
    request.setHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(body)))
    request
  }

}
