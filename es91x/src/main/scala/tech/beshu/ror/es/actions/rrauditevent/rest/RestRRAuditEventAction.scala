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
package tech.beshu.ror.es.actions.rrauditevent.rest

import org.elasticsearch.ElasticsearchException
import org.elasticsearch.client.internal.node.NodeClient
import org.elasticsearch.common.bytes.BytesReference
import org.elasticsearch.common.xcontent.XContentHelper
import org.elasticsearch.rest.*
import org.elasticsearch.rest.BaseRestHandler.RestChannelConsumer
import org.elasticsearch.rest.RestHandler.Route
import org.elasticsearch.rest.RestRequest.Method.POST
import org.elasticsearch.xcontent.{DeprecationHandler, NamedXContentRegistry, XContentFactory, XContentParser, XContentParserConfiguration, XContentType}
import org.json.JSONObject
import squants.information.{Bytes, Information}
import tech.beshu.ror.constants
import tech.beshu.ror.es.actions.rrauditevent.{RRAuditEventActionType, RRAuditEventRequest}

import java.util
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

class RestRRAuditEventAction
  extends BaseRestHandler with RestHandler {

  private val strictParserConfig = XContentParserConfiguration.EMPTY
    .withRegistry(NamedXContentRegistry.EMPTY)
    .withDeprecationHandler(DeprecationHandler.THROW_UNSUPPORTED_OPERATION)

  override def routes(): util.List[Route] = List(
    new Route(POST, constants.AUDIT_EVENT_COLLECTOR_PATH)
  ).asJava

  override val getName: String = "ror-audit-event-collector-handler"

  override def prepareRequest(request: RestRequest,
                              client: NodeClient): RestChannelConsumer = new RestChannelConsumer {
    private val rorAuditRequest = for {
      _ <- validateContentSize(request)
      json <- validateBodyJson(request)
    } yield new RRAuditEventRequest(json)

    override def accept(channel: RestChannel): Unit = {
      val listener = new RestRRAuditEventActionResponseBuilder(channel)
      rorAuditRequest match {
        case Right(req) =>
          client.execute(new RRAuditEventActionType, req, listener)
        case Left(error) =>
          listener.onFailure(error)
      }
    }
  }

  private def validateContentSize(request: RestRequest) = {
    Either.cond(
      request.content().length() <= constants.MAX_AUDIT_EVENT_REQUEST_CONTENT_IN_BYTES,
      (),
      new AuditEventRequestPayloadTooLarge(Bytes(constants.MAX_AUDIT_EVENT_REQUEST_CONTENT_IN_BYTES.toInt))
    )
  }

  private def validateBodyJson(request: RestRequest) = Try {
    if (request.hasContent) {
      new JSONObject(asStrictMap(request.requiredContent(), request.getXContentType).asJava)
    } else {
      new JSONObject()
    }
  }.toEither.left.map(_ => new AuditEventBadRequest)

  private def asStrictMap(bytes: BytesReference, contentType: XContentType): Map[String, Any] = {
    val map = XContentHelper.convertToMap(bytes, false, contentType).v2().asScala
    if (map.nonEmpty || isExactlyEmptyObject(bytes, contentType)) map.toMap
    else throw new IllegalArgumentException("Malformed request body")
  }

  private def isExactlyEmptyObject(bytes: BytesReference, t: XContentType): Boolean = {
    Using.resource(XContentFactory.xContent(t).createParser(strictParserConfig, bytes.streamInput())) { p =>
      (p.nextToken() == XContentParser.Token.START_OBJECT) &&
        (p.nextToken() == XContentParser.Token.END_OBJECT) &&
        p.nextToken() == null // no trailing data
    }
  }

  private class AuditEventBadRequest extends ElasticsearchException("Content malformed") {
    override def status(): RestStatus = RestStatus.BAD_REQUEST
  }

  private class AuditEventRequestPayloadTooLarge(maxContentSize: Information)
    extends ElasticsearchException(s"Max request content allowed = ${maxContentSize.toKilobits}KB") {
    override def status(): RestStatus = RestStatus.REQUEST_ENTITY_TOO_LARGE
  }

}
