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
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.xcontent.XContentHelper
import org.elasticsearch.rest.BaseRestHandler.RestChannelConsumer
import org.elasticsearch.rest._
import org.json.JSONObject
import squants.information.{Bytes, Information}
import tech.beshu.ror.constants
import tech.beshu.ror.es.actions.rrauditevent.{RRAuditEventActionType, RRAuditEventRequest}

import scala.util.Try

@Inject
class RestRRAuditEventAction(settings: Settings, controller: RestController)
  extends BaseRestHandler(settings) with RestHandler {

  register("POST", constants.AUDIT_EVENT_COLLECTOR_PATH)

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

  private def register(method: String, path: String): Unit =
    controller.registerHandler(RestRequest.Method.valueOf(method), path, this)

  private def validateContentSize(request: RestRequest) = {
    Either.cond(
      request.content().length() <= constants.MAX_AUDIT_EVENT_REQUEST_CONTENT_IN_BYTES,
      (),
      new AuditEventRequestPayloadTooLarge(Bytes(constants.MAX_AUDIT_EVENT_REQUEST_CONTENT_IN_BYTES.toInt))
    )
  }

  private def validateBodyJson(request: RestRequest) = Try {
    if (request.hasContent) {
      new JSONObject(XContentHelper.convertToMap(request.requiredContent(), false, request.getXContentType).v2())
    } else {
      new JSONObject()
    }
  }.toEither.left.map(_ => new AuditEventBadRequest)

  private class AuditEventBadRequest extends ElasticsearchException("Content malformed") {
    override def status(): RestStatus = RestStatus.BAD_REQUEST
  }

  private class AuditEventRequestPayloadTooLarge(maxContentSize: Information)
    extends ElasticsearchException(s"Max request content allowed = ${maxContentSize.toKilobits}KB") {
    override def status(): RestStatus = RestStatus.REQUEST_ENTITY_TOO_LARGE
  }

}
