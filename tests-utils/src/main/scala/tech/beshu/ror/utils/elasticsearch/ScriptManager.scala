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

import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.httpclient.RestClient
import ujson.Value

import scala.util.Try

class ScriptManager(client: RestClient, esVersion: String)
  extends BaseManager(client, esVersion, esNativeApi = true) {

  def store(endpoint: String, query: String): StoreResult =
    call(createStoreRequest(endpoint, query), new StoreResult(_))

  private def createStoreRequest(endpoint: String, query: String) = {
    val request = new HttpPost(client.from(endpoint))
    request.setHeader("timeout", "50s")
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(query))
    request
  }

  class StoreResult(response: HttpResponse) extends JsonResponse(response) {
    lazy val searchHits: Try[Value] = Try(responseJson("hits")("hits"))
  }
}