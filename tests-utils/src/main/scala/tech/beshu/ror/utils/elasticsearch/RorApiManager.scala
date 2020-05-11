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

import org.apache.http.client.methods.HttpGet
import tech.beshu.ror.utils.elasticsearch.BaseManager.JsonResponse
import tech.beshu.ror.utils.httpclient.RestClient

class RorApiManager(client: RestClient)
  extends BaseManager(client) {

  def fetchMetadata(): JsonResponse = {
    call(createUserMetadataRequest(None), new JsonResponse(_))
  }

  def fetchMetadata(preferredGroup: String): JsonResponse = {
    call(createUserMetadataRequest(Some(preferredGroup)), new JsonResponse(_))
  }

  private def createUserMetadataRequest(preferredGroup: Option[String]) = {
    val request = new HttpGet(client.from("/_readonlyrest/metadata/current_user"))
    preferredGroup.foreach(request.addHeader("x-ror-current-group", _))
    request
  }

}
