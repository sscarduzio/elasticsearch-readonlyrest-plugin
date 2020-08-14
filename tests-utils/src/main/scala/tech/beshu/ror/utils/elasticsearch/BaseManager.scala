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

import com.typesafe.scalalogging.LazyLogging
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpUriRequest
import tech.beshu.ror.utils.elasticsearch.BaseManager.SimpleResponse
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.HttpResponseHelper.stringBodyFrom
import tech.beshu.ror.utils.misc.ScalaUtils._
import ujson.Value

import scala.util.Try

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

  final case class SimpleHeader(name: String, value: String)

  class SimpleResponse private[elasticsearch](response: HttpResponse) {
    val headers: List[SimpleHeader] = response.getAllHeaders.map(h => SimpleHeader(h.getName, h.getValue)).toList
    val responseCode: Int = response.getStatusLine.getStatusCode
    val isSuccess: Boolean = responseCode / 100 == 2
    val isForbidden: Boolean = responseCode == 401
    val isNotFound: Boolean = responseCode == 404
    val isBadRequest: Boolean = responseCode == 400
    lazy val body: String = stringBodyFrom(response)

    def force(): Unit = {
      if(!isSuccess) throw new IllegalStateException(
        s"Expected success but got HTTP $responseCode, body: ${Try(stringBodyFrom(response)).getOrElse("")}"
      )
    }
  }

  class JsonResponse(response: HttpResponse) extends SimpleResponse(response) with LazyLogging {
    val responseJson: JSON = ujson.read(body)
  }
}