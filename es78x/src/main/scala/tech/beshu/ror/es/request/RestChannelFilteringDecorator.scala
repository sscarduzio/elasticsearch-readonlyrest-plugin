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
package tech.beshu.ror.es.request

import org.elasticsearch.common.util.concurrent.ThreadContext
import org.elasticsearch.common.xcontent.cbor.CborXContent
import org.elasticsearch.common.xcontent.json.JsonXContent
import org.elasticsearch.common.xcontent.smile.SmileXContent
import org.elasticsearch.common.xcontent.yaml.YamlXContent
import org.elasticsearch.common.xcontent.{LoggingDeprecationHandler, NamedXContentRegistry, XContentBuilder, XContentType}
import org.elasticsearch.rest._
import tech.beshu.ror.accesscontrol.domain.ResponseFieldsFiltering.{AccessMode, ResponseFieldsRestrictions}

import scala.collection.JavaConverters._

class RestChannelFilteringDecorator(channel: RestChannel, request: RestRequest, threadContext: ThreadContext, detailedErrorsEnabled: Boolean)
  extends AbstractRestChannel(request, detailedErrorsEnabled) {

  private var responseFieldsRestrictions: Option[ResponseFieldsRestrictions] = None

  def setResponseFieldRestrictions(responseFieldsRestrictions: ResponseFieldsRestrictions): Unit = {
    this.responseFieldsRestrictions = Some(responseFieldsRestrictions)
  }

  override def sendResponse(response: RestResponse): Unit = {
    response match {
      case bytesRestResponse: BytesRestResponse =>
        responseFieldsRestrictions match {
          case Some(fieldsRestrictions) =>
            channel.sendResponse(filterBytesRestResponse(bytesRestResponse, fieldsRestrictions))
          case None =>
            channel.sendResponse(bytesRestResponse)
        }
      case otherResponse => channel.sendResponse(otherResponse)
    }
  }

  private def filterBytesRestResponse(response: BytesRestResponse, fieldsRestrictions: ResponseFieldsRestrictions): BytesRestResponse = {
    val (includes, excludes) = fieldsRestrictions.mode match {
      case AccessMode.Whitelist =>
        (fieldsRestrictions.documentFields.map(_.value.value), Set.empty[String])
      case AccessMode.Blacklist =>
        (Set.empty[String], fieldsRestrictions.documentFields.map(_.value.value))
    }
    val xContent =
      if(response.contentType().equals(XContentType.JSON.mediaType())) JsonXContent.jsonXContent
      else if (response.contentType().equals(XContentType.YAML.mediaType())) YamlXContent.yamlXContent
      else if (response.contentType().equals(XContentType.CBOR.mediaType())) CborXContent.cborXContent
      else if (response.contentType().equals(XContentType.SMILE.mediaType())) SmileXContent.smileXContent
      else throw new IllegalStateException("Unknown response content type")

    val parser = xContent.createParser(NamedXContentRegistry.EMPTY, LoggingDeprecationHandler.INSTANCE, response.content().streamInput())
    val contentBuilder = XContentBuilder.builder(xContent, includes.asJava, excludes.asJava)
    contentBuilder.copyCurrentStructure(parser)
    contentBuilder.flush()
    new BytesRestResponse(response.status(), contentBuilder)
  }

}
