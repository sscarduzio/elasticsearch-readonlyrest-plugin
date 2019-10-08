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
import org.apache.http.client.methods.HttpUriRequest
import tech.beshu.ror.utils.elasticsearch.BaseManager.SimpleResponse
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.HttpResponseHelper.stringBodyFrom
import tech.beshu.ror.utils.misc.ScalaUtils._
import ujson.Value

abstract class BaseManager(client: RestClient) {

  protected def call[T <: SimpleResponse](request: HttpUriRequest, fromResponse: HttpResponse => T): T = {
    client
      .execute {
        additionalHeaders.foldLeft(request) {
          case (req, (name, value)) =>
            req.addHeader(name, value)
            req
        }
      }
      .bracket(fromResponse)
  }

  protected def additionalHeaders: Map[String, String] = Map.empty

}

object BaseManager {

  type JSON = Value

  class SimpleResponse private[elasticsearch](response: HttpResponse) {
    val responseCode: Int = response.getStatusLine.getStatusCode
    val isSuccess: Boolean = responseCode / 100 == 2
    val isForbidden: Boolean = responseCode == 403
  }

  class JsonResponse(response: HttpResponse) extends SimpleResponse(response) {
    val body: String = stringBodyFrom(response)
    val responseJson: JSON = ujson.read(body)
  }
}