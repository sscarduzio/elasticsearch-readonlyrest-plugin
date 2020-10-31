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

import eu.timepit.refined.types.string.NonEmptyString
import org.elasticsearch.common.util.concurrent.ThreadContext
import org.elasticsearch.common.xcontent.cbor.CborXContent
import org.elasticsearch.common.xcontent.json.JsonXContent
import org.elasticsearch.common.xcontent.smile.SmileXContent
import org.elasticsearch.common.xcontent.yaml.YamlXContent
import org.elasticsearch.common.xcontent.{LoggingDeprecationHandler, NamedXContentRegistry, XContentBuilder, XContentType}
import org.elasticsearch.rest.{AbstractRestChannel, BytesRestResponse, RestChannel, RestRequest, RestResponse}
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.domain.FieldsRestrictions
import tech.beshu.ror.accesscontrol.domain.FieldsRestrictions.AccessMode
import tech.beshu.ror.accesscontrol.headerValues.transientFieldsFromHeaderValue

import scala.util.{Failure, Success}
import scala.collection.JavaConverters._

class RestChannelFilteringDecorator(channel: RestChannel, request: RestRequest, threadContext: ThreadContext, detailedErrorsEnabled: Boolean)
  extends AbstractRestChannel(request, detailedErrorsEnabled) {

  override def sendResponse(response: RestResponse): Unit = {
    response match {
      case bytesRestResponse: BytesRestResponse =>
        Option(threadContext.getHeader(Constants.RESPONSE_FIELDS_TRANSIENT)) match {
          case Some(fieldsHeader) =>
            fieldsFromHeaderValue(fieldsHeader) match {
              case Success(fieldsRestrictions) =>
                channel.sendResponse(filterBytesRestResponse(bytesRestResponse, fieldsRestrictions))
              case Failure(ex) =>
                throw ex
            }
          case None =>
            channel.sendResponse(bytesRestResponse)
        }
      case otherResponse => channel.sendResponse(otherResponse)
    }
  }

  private def filterBytesRestResponse(response: BytesRestResponse, fieldsRestrictions: FieldsRestrictions): BytesRestResponse = {
    val (includes, excludes) = fieldsRestrictions.mode match {
      case AccessMode.Whitelist =>
        (fieldsRestrictions.fields.map(_.value.value), Set.empty[String])
      case AccessMode.Blacklist =>
        (Set.empty[String], fieldsRestrictions.fields.map(_.value.value))
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

  private def fieldsFromHeaderValue(value: String) = {
    lazy val failure = Failure(new IllegalStateException("Couldn't extract response_fields from ThreadContext"))
    for {
      nel <- NonEmptyString.from(value).fold(_ => failure, Success(_))
      fields <- transientFieldsFromHeaderValue.fromRawValue(nel).fold(_ => failure, Success(_))
    } yield fields
  }
}
