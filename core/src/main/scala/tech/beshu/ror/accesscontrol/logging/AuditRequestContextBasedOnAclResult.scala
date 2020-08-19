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
package tech.beshu.ror.accesscontrol.logging

import java.time.Instant

import cats.Show
import tech.beshu.ror.accesscontrol.blocks.Block.History
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{GeneralIndexRequestBlockContext, SnapshotRequestBlockContext, TemplateRequestBlockContext}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.accesscontrol.domain.{Address, Header}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.accesscontrol.show.logs.{historyShow, obfuscatedHeaderShow}
import tech.beshu.ror.audit.{AuditRequestContext, Headers}

class AuditRequestContextBasedOnAclResult[B <: BlockContext](requestContext: RequestContext.Aux[B],
                                                             blockContext: Option[B],
                                                             historyEntries: Vector[History[B]],
                                                             loggingContext: LoggingContext)
  extends AuditRequestContext {

  implicit val showHeader: Show[Header] = obfuscatedHeaderShow(loggingContext.obfuscatedHeaders)
  override val timestamp: Instant = requestContext.timestamp
  override val id: String = requestContext.id.value
  override val indices: Set[String] = requestContext.initialBlockContext.indices.map(_.value.value)
  override val action: String = requestContext.action.value
  override val headers: Map[String, String] = requestContext.headers.map(h => (h.name.value.value, h.value.value)).toMap
  override val requestHeaders: Headers = new Headers(
    requestContext.headers
      .foldLeft(Map.empty[String, Set[String]]) {
        case (acc, header) =>
          val headerNames = acc.get(header.name.value.value).toList.flatten.toSet
          acc + (header.name.value.value -> (headerNames + header.value.value))
      }
  )
  override val uriPath: String = requestContext.uriPath.value
  override val history: String = historyEntries.map(h => historyShow(showHeader).show(h)).mkString(", ")
  override val content: String = requestContext.content
  override val contentLength: Integer = requestContext.contentLength.toBytes.toInt
  override val remoteAddress: String = requestContext.remoteAddress match {
    case Some(Address.Ip(value)) => value.toString
    case Some(Address.Name(value)) => value.toString
    case None => "N/A"
  }
  override val localAddress: String = requestContext.localAddress match {
    case Address.Ip(value) => value.toString
    case Address.Name(value) => value.toString
  }
  override val `type`: String = requestContext.`type`.value
  override val taskId: Long = requestContext.taskId
  override val httpMethod: String = requestContext.method.m
  override val loggedInUserName: Option[String] = blockContext.flatMap(_.userMetadata.loggedUser.map(_.id.value.value))
  override val impersonatedByUserName: Option[String] =
    blockContext
      .flatMap(_.userMetadata.loggedUser)
      .flatMap {
        case DirectlyLoggedUser(_) => None
        case ImpersonatedUser(_, impersonatedBy) => Some(impersonatedBy.value.value)
      }
  override val involvesIndices: Boolean = blockContext match {
    case Some(_: GeneralIndexRequestBlockContext | _: TemplateRequestBlockContext | _: SnapshotRequestBlockContext) => true
    case _ => false
  }
  override val attemptedUserName: Option[String] = requestContext.basicAuth.map(_.credentials.user.value.value)
  override val rawAuthHeader: Option[String] = requestContext.rawAuthHeader.map(_.value.value)
}
