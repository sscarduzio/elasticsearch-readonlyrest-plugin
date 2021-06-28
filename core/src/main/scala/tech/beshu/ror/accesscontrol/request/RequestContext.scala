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
package tech.beshu.ror.accesscontrol.request

import java.time.Instant

import cats.Show
import cats.implicits._
import com.softwaremill.sttp.Method
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.scala.Logging
import org.json.JSONObject
import squants.information.{Bytes, Information}
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext.Id
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.language.implicitConversions

trait RequestContext {

  type BLOCK_CONTEXT <: BlockContext

  def initialBlockContext: BLOCK_CONTEXT

  def timestamp: Instant

  def taskId: Long

  def id: Id

  def `type`: Type

  def action: Action

  def headers: Set[Header]

  def remoteAddress: Option[Address]

  def localAddress: Address

  def method: Method

  def uriPath: UriPath

  def contentLength: Information

  def content: String

  def allIndicesAndAliases: Set[FullLocalIndexWithAliases]

  def allRemoteIndicesAndAliases: Task[Set[FullRemoteIndexWithAliases]]

  def allTemplates: Set[Template]

  def allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]]

  lazy val legacyTemplates: Set[Template.LegacyTemplate] =
    allTemplates.collect { case t: Template.LegacyTemplate => t }

  lazy val indexTemplates: Set[Template.IndexTemplate] =
    allTemplates.collect { case t: Template.IndexTemplate => t }

  lazy val componentTemplates: Set[Template.ComponentTemplate] =
    allTemplates.collect { case t: Template.ComponentTemplate => t }

  def isReadOnlyRequest: Boolean

  def isCompositeRequest: Boolean

  def isAllowedForDLS: Boolean

  def generalAuditEvents: JSONObject = new JSONObject()

  lazy val correlationId: CorrelationId =
    headers
      .find(_.name === Header.Name.correlationId)
      .map(_.value)
      .map(CorrelationId.apply)
      .getOrElse(CorrelationId.random)
}

object RequestContext extends Logging {

  type Aux[B <: BlockContext] = RequestContext { type BLOCK_CONTEXT = B }

  final case class Id(value: String) extends AnyVal
  object Id {
    implicit val show: Show[Id] = Show.show(_.value)
  }

  def show[B <: BlockContext](loggedUser: Option[LoggedUser],
                              kibanaIndex: Option[ClusterIndexName],
                              history: Vector[Block.History[B]])
                             (implicit headerShow: Show[Header]): Show[RequestContext.Aux[B]] =
    Show.show { r =>
      def stringifyUser = {
        loggedUser match {
          case Some(DirectlyLoggedUser(user)) => s"${user.show}"
          case Some(ImpersonatedUser(user, impersonatedBy)) => s"${impersonatedBy.show} (as ${user.show})"
          case None => r.basicAuth.map(_.credentials.user.value).map(name => s"${name.value} (attempted)").getOrElse("[no info about user]")
        }
      }

      def stringifyContentLength = {
        if (r.contentLength == Bytes(0)) "<N/A>"
        else if (logger.delegate.isEnabled(Level.DEBUG)) r.content
        else s"<OMITTED, LENGTH=${r.contentLength}> "
      }

      def stringifyIndices = {
        val idx = r.initialBlockContext.indices.toList.map(_.show)
        if (idx.isEmpty) "<N/A>"
        else idx.mkString(",")
      }

      s"""{
         | ID:${r.id.show},
         | TYP:${r.`type`.show},
         | CGR:${r.currentGroup.show},
         | USR:$stringifyUser,
         | BRS:${r.headers.exists(_.name === Header.Name.userAgent)},
         | KDX:${kibanaIndex.map(_.show).getOrElse("null")},
         | ACT:${r.action.show},
         | OA:${r.remoteAddress.map(_.show).getOrElse("null")},
         | XFF:${r.headers.find(_.name === Header.Name.xForwardedFor).map(_.value.show).getOrElse("null")},
         | DA:${r.localAddress.show},
         | IDX:$stringifyIndices,
         | MET:${r.method.show},
         | PTH:${r.uriPath.show},
         | CNT:$stringifyContentLength,
         | HDR:${r.headers.map(_.show).toList.sorted.mkString(", ")},
         | HIS:${history.map(h => historyShow(headerShow).show(h)).mkString(", ")},
         | }""".oneLiner
    }
}

class RequestContextOps(val requestContext: RequestContext) extends AnyVal {

  type LocalAliasName = ClusterIndexName.Local

  def impersonateAs: Option[User.Id] = {
    findHeader(Header.Name.impersonateAs)
      .map { header => User.Id(header.value) }
  }

  def xForwardedForHeaderValue: Option[Address] = {
    findHeader(Header.Name.xForwardedFor)
      .flatMap { header =>
        Option(header.value.value)
          .flatMap(_.split(",").headOption)
          .flatMap(Address.from)
      }
  }

  def currentGroup: RequestGroup = {
    findHeader(Header.Name.currentGroup) match {
      case None => RequestGroup.`N/A`
      case Some(Header(_, value)) => RequestGroup.AGroup(Group(value))
    }
  }

  def isCurrentGroupEligible(groups: UniqueNonEmptyList[Group]): Boolean = {
    currentGroup match {
      case RequestGroup.AGroup(preferredGroup) =>
        requestContext.uriPath.isCurrentUserMetadataPath || groups.contains(preferredGroup)
      case RequestGroup.`N/A` =>
        true
    }
  }

  def basicAuth: Option[BasicAuth] = {
    requestContext
      .headers
      .toStream
      .map(BasicAuth.fromHeader)
      .find(_.isDefined)
      .flatten
  }

  def rawAuthHeader: Option[Header] = findHeader(Header.Name.authorization)

  def bearerToken: Option[AuthorizationToken] = authorizationToken {
    AuthorizationTokenDef(Header.Name.authorization, "Bearer ")
  }

  def authorizationToken(config: AuthorizationTokenDef): Option[AuthorizationToken] = {
    requestContext
      .headers
      .find(_.name === config.headerName)
      .flatMap { h =>
        if (h.value.value.startsWith(config.prefix)) {
          NonEmptyString
            .unapply(h.value.value.substring(config.prefix.length))
            .map(AuthorizationToken.apply)
        } else {
          None
        }
      }
  }

  private def findHeader(name: Header.Name) = requestContext.headers.find(_.name === name)
}

object RequestContextOps {
  implicit def from(rc: RequestContext): RequestContextOps = new RequestContextOps(rc)

  sealed trait RequestGroup
  object RequestGroup {
    final case class AGroup(userGroup: Group) extends RequestGroup
    case object `N/A` extends RequestGroup

    implicit val show: Show[RequestGroup] = Show.show {
      case AGroup(group) => group.value.value
      case `N/A` => "N/A"
    }

    implicit class ToOption(val requestGroup: RequestGroup) extends AnyVal {
      def toOption: Option[Group] = requestGroup match {
        case AGroup(userGroup) => Some(userGroup)
        case `N/A` => None
      }
    }
  }
}
