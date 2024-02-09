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
import org.elasticsearch.rest.{AbstractRestChannel, RestChannel, RestResponse}
import tech.beshu.ror.es.utils.ThreadRepo

class RorRestChannel(underlying: RestChannel)
  extends AbstractRestChannel(underlying.request(), true)
    with ResponseFieldsFiltering
    with Logging {

  override def sendResponse(response: RestResponse): Unit = {
    ThreadRepo.removeRestChannel(this)
    underlying.sendResponse(
      addXElasticProductHeaderIfMissing(filterRestResponse(response))
    )
  }

  private def addXElasticProductHeaderIfMissing(response: RestResponse) = {
    Option(underlying.request().header("X-elastic-product-origin")) match {
      case Some(_) =>
        Option(response.getHeaders.get("X-elastic-product")) match {
          case Some(_) =>
          case None =>
            import scala.jdk.CollectionConverters._
            val headers = underlying.request().getHeaders.asScala.map { case (h, v) => s"$h:${v.asScala.mkString(",")}" }.mkString(",")
            println(s"Request headers: $headers")
            println(s"Adding header to ${underlying.request().method().name()} ${underlying.request().path()}")
            response.addHeader("X-elastic-product", "Elasticsearch")
        }
      case None =>
    }
    response
  }
}
