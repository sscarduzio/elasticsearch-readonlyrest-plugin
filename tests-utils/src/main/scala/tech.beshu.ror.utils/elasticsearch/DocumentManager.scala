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
import tech.beshu.ror.utils.elasticsearch.BaseManager.{JSON, JsonResponse}
import tech.beshu.ror.utils.httpclient.RestClient

import scala.collection.JavaConverters._

class DocumentManager(restClient: RestClient)
  extends BaseManager(restClient) {

  def createDoc(docPath: String, content: JSON): JsonResponse = {
    call(createInsertDocRequest(docPath, content, waitForRefresh = true), new JsonResponse(_))
  }

  def createDocAndAssert(docPath: String, content: JSON): Unit = {
    val createDocResult = createDoc(docPath, content)
    if(!createDocResult.isSuccess || createDocResult.responseJson("result").str != "created") {
      throw new IllegalStateException(s"Cannot create document '$docPath'; returned: ${createDocResult.body}")
    }
  }

  private def createInsertDocRequest(docPath: String, content: JSON, waitForRefresh: Boolean) = {
    val queryParams =
      if (waitForRefresh) Map("refresh" -> "wait_for")
      else Map.empty[String, String]
    val request = new HttpPut(restClient.from(docPath, queryParams.asJava))
    request.setHeader("timeout", "50s")
    request.addHeader("Content-type", "application/json")
    request.setEntity(new StringEntity(ujson.write(content)))
    request
  }
}
