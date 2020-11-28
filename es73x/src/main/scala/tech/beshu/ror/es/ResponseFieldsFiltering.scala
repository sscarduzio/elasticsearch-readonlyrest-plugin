package tech.beshu.ror.es

import monix.execution.atomic.Atomic
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.common.xcontent.cbor.CborXContent
import org.elasticsearch.common.xcontent.json.JsonXContent
import org.elasticsearch.common.xcontent.smile.SmileXContent
import org.elasticsearch.common.xcontent.yaml.YamlXContent
import org.elasticsearch.common.xcontent.{LoggingDeprecationHandler, NamedXContentRegistry, XContentBuilder, XContentType}
import org.elasticsearch.rest.{BytesRestResponse, RestResponse}
import tech.beshu.ror.accesscontrol.domain.ResponseFieldsFiltering.{AccessMode, ResponseFieldsRestrictions}

import scala.collection.JavaConverters._

trait ResponseFieldsFiltering {
  this: Logging =>

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
            logger.warn("ResponseFields filtering is unavailable for this type of request")
            otherResponse
        }
      case None =>
        response
    }
  }

  private def filterBytesRestResponse(response: BytesRestResponse, fieldsRestrictions: ResponseFieldsRestrictions): BytesRestResponse = {
    val (includes, excludes) = fieldsRestrictions.mode match {
      case AccessMode.Whitelist =>
        (fieldsRestrictions.responseFields.map(_.value.value), Set.empty[String])
      case AccessMode.Blacklist =>
        (Set.empty[String], fieldsRestrictions.responseFields.map(_.value.value))
    }
    val xContent =
      if(response.contentType().contains(XContentType.JSON.mediaTypeWithoutParameters())) JsonXContent.jsonXContent
      else if (response.contentType().contains(XContentType.YAML.mediaTypeWithoutParameters())) YamlXContent.yamlXContent
      else if (response.contentType().contains(XContentType.CBOR.mediaTypeWithoutParameters())) CborXContent.cborXContent
      else if (response.contentType().contains(XContentType.SMILE.mediaTypeWithoutParameters())) SmileXContent.smileXContent
      else throw new IllegalStateException("Unknown response content type")

    val parser = xContent.createParser(NamedXContentRegistry.EMPTY, LoggingDeprecationHandler.INSTANCE, response.content().streamInput())
    val contentBuilder = XContentBuilder.builder(xContent, includes.asJava, excludes.asJava)
    contentBuilder.copyCurrentStructure(parser)
    contentBuilder.flush()
    new BytesRestResponse(response.status(), contentBuilder)
  }
}
