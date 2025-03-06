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
package tech.beshu.ror.es

import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.http.HttpChannel
import org.elasticsearch.rest.{AbstractRestChannel, RestChannel as EsRestChannel, RestRequest as EsRestRequest, RestResponse as EsRestResponse}
import squants.information.{Bytes, Information}
import tech.beshu.ror.accesscontrol.domain.{Header, UriPath}
import tech.beshu.ror.accesscontrol.request.RequestContext.Method
import tech.beshu.ror.es.utils.ThreadRepo
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.RefinedUtils.nes

import scala.jdk.CollectionConverters.*

final class RorRestChannel(underlying: EsRestChannel)
  extends AbstractRestChannel(underlying.request(), true)
    with ResponseFieldsFiltering
    with Logging {

  val restRequest: RorRestRequest = new RorRestRequest(underlying.request())

  override def sendResponse(response: EsRestResponse): Unit = {
    ThreadRepo.removeRestChannel(this)
    underlying.sendResponse(filterRestResponse(response))
  }
}

final class RorRestRequest(underlying: EsRestRequest) {

  lazy val method: Method = Method.fromStringUnsafe(underlying.method().name())
  
  lazy val path: UriPath = UriPath
    .from(underlying.path())
    .getOrElse(UriPath.from(nes("/")))
  
  lazy val allHeaders: Set[Header] = Header.fromRawHeaders(
    underlying
    .getHeaders.asScala
    .view.mapValues(_.asScala.toList)
    .toMap
  )
  
  lazy val httpChannel: HttpChannel = underlying.getHttpChannel

  val content: String = Option(underlying.content()).map(_.utf8ToString()).getOrElse("")
  
  val contentLength: Information = Bytes(underlying.contentLength())
}
