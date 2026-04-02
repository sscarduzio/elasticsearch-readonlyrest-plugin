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
package tech.beshu.ror.accesscontrol.audit

import cats.Show
import cats.data.NonEmptyList
import org.json.JSONObject
import tech.beshu.ror.accesscontrol.History
import tech.beshu.ror.accesscontrol.History.BlockHistory
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.accesscontrol.domain.{Address, Header, LoggedUser}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.audit.{AuditEnvironmentContext, AuditRequestContext, Headers}
import tech.beshu.ror.implicits.*

import java.time.Instant
import scala.collection.immutable.Set as ScalaSet

private[audit] class AuditRequestContextBasedOnAclResult[B <: BlockContext](requestContext: RequestContext.Aux[B],
                                                                            loggedUser: Option[LoggedUser],
                                                                            matchedBlocks: Option[NonEmptyList[Block]],
                                                                            aclProcessingHistory: History[B],
                                                                            loggingContext: LoggingContext,
                                                                            override val auditEnvironmentContext: AuditEnvironmentContext,
                                                                            override val generalAuditEvents: JSONObject,
                                                                            override val involvesIndices: Boolean)
  extends AuditRequestContext {

  implicit val showHeader: Show[Header] = obfuscatedHeaderShow(loggingContext.obfuscatedHeaders)

  override val timestamp: Instant = requestContext.timestamp
  override val id: String = requestContext.id.value
  override val correlationId: String = requestContext.rorKibanaSessionId.value.value
  override lazy val indices: ScalaSet[String] = requestContext.requestedIndices match {
    case Some(indices) => indices.map(_.stringify).toSet
    case None => ScalaSet.empty
  }
  override val action: String = requestContext.action.value
  override lazy val headers: Map[String, String] =
    requestContext.restRequest.allHeaders
      .map(h => (h.name.value.value, h.value.value))
      .toMap
  override lazy val requestHeaders: Headers = new Headers(
    requestContext.restRequest.allHeaders
      .foldLeft(Map.empty[String, ScalaSet[String]]) {
        case (acc, header) =>
          val key = header.name.value.value
          acc.updated(key, acc.getOrElse(key, ScalaSet.empty) + header.value.value)
      }
  )
  override val uriPath: String = requestContext.restRequest.path.value.value
  override val matchedBlockNames: Option[List[String]] = matchedBlocks.map(_.map(_.name.value).toList)
  override lazy val history: String = iterableLikeShow(blockHistoryShow(showHeader)).show(aclProcessingHistory.blocks)
  override lazy val blocksHistory: Map[String, (Boolean, Option[String])] =
    aclProcessingHistory.blocks
      .map { h =>
        val blockName = h.block.name.value
        val matchedAndCause = h match {
          case BlockHistory.Permitted(_, _, _) => true -> None
          case BlockHistory.Denied(_, denied, _) => false -> Some(denied.cause.show)
        }
        blockName -> matchedAndCause
      }
      .toMap
  override lazy val content: String = requestContext.restRequest.content
  override val contentLength: Integer = requestContext.restRequest.contentLength.toBytes.toInt
  override val remoteAddress: String = requestContext.restRequest.remoteAddress match {
    case Some(Address.Ip(value)) => value.toString
    case Some(Address.Name(value)) => value.toString
    case None => "N/A"
  }
  override val localAddress: String = requestContext.restRequest.localAddress match {
    case Address.Ip(value) => value.toString
    case Address.Name(value) => value.toString
  }
  override val `type`: String = requestContext.`type`.value
  override val taskId: Long = requestContext.taskId
  override val httpMethod: String = requestContext.restRequest.method.value
  override val loggedInUserName: Option[String] = loggedUser.map(_.id.value.value)
  override val impersonatedByUserName: Option[String] =
    loggedUser
      .flatMap {
        case DirectlyLoggedUser(_) => None
        case ImpersonatedUser(_, impersonatedBy) => Some(impersonatedBy.value.value)
      }
  override val attemptedUserName: Option[String] = requestContext.basicAuth.map(_.credentials.user.value.value)
  override val rawAuthHeader: Option[String] = requestContext.rawAuthHeader.map(_.value.value)
}