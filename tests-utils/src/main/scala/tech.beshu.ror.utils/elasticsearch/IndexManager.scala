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

import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import tech.beshu.ror.utils.elasticsearch.BaseManager.JsonResponse
import tech.beshu.ror.utils.httpclient.RestClient

class IndexManager(client: RestClient)
  extends BaseManager(client) {

  def createAliasOf(index: String, alias: String): JsonResponse = {
    call(createAliasRequest(index, alias), new JsonResponse(_))
  }

  def createAliasAndAssert(index: String, alias: String): Unit = {
    val result = createAliasOf(index, alias)
    if(!result.isSuccess) {
      throw new IllegalStateException(s"Cannot create alias '$alias'; returned: ${result.body}")
    }
  }

  private def createAliasRequest(index: String, alias: String) = {
    val request = new HttpPost(client.from("_aliases"))
    request.addHeader("Content-type", "application/json")
    request.setEntity(new StringEntity(
      s"""{"actions":[{"add":{"index":"$index","alias":"$alias"}}]}""".stripMargin))
    request
  }
}
