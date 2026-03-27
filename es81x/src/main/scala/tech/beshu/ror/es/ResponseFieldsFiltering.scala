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

import monix.execution.atomic.Atomic
import tech.beshu.ror.utils.RequestIdAwareLogging
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler
import org.elasticsearch.rest.{BytesRestResponse, RestResponse}
import org.elasticsearch.xcontent.{NamedXContentRegistry, XContentBuilder, XContentParserConfiguration, XContentType}
import org.elasticsearch.xcontent.cbor.CborXContent
import org.elasticsearch.xcontent.json.JsonXContent
import org.elasticsearch.xcontent.smile.SmileXContent
import org.elasticsearch.xcontent.yaml.YamlXContent
import tech.beshu.ror.accesscontrol.domain.ResponseFieldsFiltering.{AccessMode, ResponseFieldsRestrictions}

import scala.jdk.CollectionConverters.*

trait ResponseFieldsFiltering {
  this: RequestIdAwareLogging =>

  private val responseFieldsRestrictions: Atomic[Option[ResponseFieldsRestrictions]] = Atomic(None: Option[ResponseFieldsRestrictions])

  def setResponseFieldRestrictions(responseFieldsRestrictions: ResponseFieldsRestrictions): Unit = {
    this.responseFieldsRestrictions.set(Some(responseFieldsRestrictions))
  }

  protected def filterRestResponse(response: RestResponse): RestResponse = {
    responseFieldsRestrictions.get() match {
      case Some(fieldsRestrictions) =>
        response match {
          case bytesRestResponse: BytesRestResponse =>
            filterBytesRestResponse(bytesRestResponse, fieldsRestrictions)
          case otherResponse =>
            noRequestIdLogger.warn("ResponseFields filtering is unavailable for this type of request")
            otherResponse
        }
      case None =>
        response
    }
  }

  private def filterBytesRestResponse(response: BytesRestResponse, fieldsRestrictions: ResponseFieldsRestrictions): BytesRestResponse = {
    val (includes, excludes) = fieldsRestrictions.mode match {
      case AccessMode.Whitelist =>
        (fieldsRestrictions.responseFields.toSet.map(_.value.value), Set.empty[String])
      case AccessMode.Blacklist =>
        (Set.empty[String], fieldsRestrictions.responseFields.toSet.map(_.value.value))
    }
    val xContent =
      if(response.contentType().contains(XContentType.JSON.mediaTypeWithoutParameters())) JsonXContent.jsonXContent
      else if (response.contentType().contains(XContentType.YAML.mediaTypeWithoutParameters())) YamlXContent.yamlXContent
      else if (response.contentType().contains(XContentType.CBOR.mediaTypeWithoutParameters())) CborXContent.cborXContent
      else if (response.contentType().contains(XContentType.SMILE.mediaTypeWithoutParameters())) SmileXContent.smileXContent
      else throw new IllegalStateException("Unknown response content type")

    val parser = xContent.createParser(
      XContentParserConfiguration.EMPTY
        .withDeprecationHandler(LoggingDeprecationHandler.INSTANCE)
        .withRegistry(NamedXContentRegistry.EMPTY),
      response.content().streamInput()
    )

    val contentBuilder = XContentBuilder.builder(xContent.`type`(), includes.asJava, excludes.asJava)
    contentBuilder.copyCurrentStructure(parser)
    contentBuilder.flush()
    new BytesRestResponse(response.status(), contentBuilder)
  }
}
