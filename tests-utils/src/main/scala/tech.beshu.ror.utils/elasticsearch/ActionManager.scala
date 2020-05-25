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

import org.apache.http.client.methods.{HttpDelete, HttpGet, HttpPost}
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.JsonResponse
import tech.beshu.ror.utils.httpclient.RestClient

class ActionManager(restClient: RestClient) extends BaseManager(restClient) {

  def actionPost(action: String, payload: String) = call(createPostActionRequest(action, payload), new JsonResponse(_))

  def actionPost(action: String) = call(new HttpPost(restClient.from("/" + action)), new JsonResponse(_))

  def actionGet(action: String) = call(new HttpGet(restClient.from("/" + action)), new JsonResponse(_))

  def actionDelete(action: String) = call(new HttpDelete(restClient.from("/" + action)), new JsonResponse(_))

  private def createPostActionRequest(action: String, payload: String) = {
    val request = new HttpPost(restClient.from("/" + action))
    request.addHeader("Content-type", "application/json")
    request.setEntity(new StringEntity(payload))
    request
  }
}