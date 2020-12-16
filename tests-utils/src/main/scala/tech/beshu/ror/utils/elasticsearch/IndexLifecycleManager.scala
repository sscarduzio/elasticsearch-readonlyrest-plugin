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
import org.apache.http.client.methods.{HttpDelete, HttpPut}
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JSON, JsonResponse, SimpleResponse}
import tech.beshu.ror.utils.elasticsearch.IndexLifecycleManager.PoliciesResponse
import tech.beshu.ror.utils.httpclient.RestClient

class IndexLifecycleManager(client: RestClient)
  extends BaseManager(client) {

  def getPolicy(id: String): PoliciesResponse = {
    call(createGetPolicyRequest(id), new PoliciesResponse(_))
  }

  def putPolicy(id: String, policy: JSON): SimpleResponse = {
    call(createPutPolicyRequest(id, policy), new SimpleResponse(_))
  }

  def deletePolicy(id: String): SimpleResponse = {
    call(createDeletePolicyRequest(id), new SimpleResponse(_))
  }

  private def createPutPolicyRequest(id: String, policy: JSON) = {
    val request = new HttpPut(client.from(s"_ilm/policy/$id"))
    request.addHeader("Content-Type", "application/json")
    request.setEntity(new StringEntity(ujson.write(policy)))
    request
  }

  private def createDeletePolicyRequest(id: String) = {
    new HttpDelete(client.from(s"_ilm/policy/$id"))
  }

  private def createGetPolicyRequest(id: String) = {
    new HttpDelete(client.from(s"_ilm/policy/$id"))
  }
}

object IndexLifecycleManager {

  class PoliciesResponse(response: HttpResponse) extends JsonResponse(response) {
    lazy val policies: Map[String, JSON] =
      responseJson.obj.toMap.map { case (policyName, json) =>
        val policy = ujson.Obj.from(json.obj.filterKeys(_ == "policy").toList)
        (policyName, policy)
      }
  }
}