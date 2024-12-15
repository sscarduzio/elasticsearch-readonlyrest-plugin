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

import cats.Show
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.scala.Logging
import org.json.JSONObject
import squants.information.{Bytes, Information}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.DataStreamName.{FullLocalDataStreamWithAliases, FullRemoteDataStreamWithAliases}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext.{Id, Method}
import tech.beshu.ror.accesscontrol.request.RequestContextOps.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

import java.time.Instant
import scala.language.implicitConversions

trait RequestContext extends Logging {

  type BLOCK_CONTEXT <: BlockContext

  def initialBlockContext: BLOCK_CONTEXT

  def timestamp: Instant

  def taskId: Long

  def id: Id

  def rorKibanaSessionId: CorrelationId

  def `type`: Type

  def action: Action

  def headers: Set[Header]

  def remoteAddress: Option[Address]

  def localAddress: Address

  def method: Method

  def uriPath: UriPath

  def contentLength: Information

  def content: String

  def indexAttributes: Set[IndexAttribute]

  def allIndicesAndAliases: Set[FullLocalIndexWithAliases]

  def allRemoteIndicesAndAliases: Task[Set[FullRemoteIndexWithAliases]]

  def allDataStreamsAndAliases: Set[FullLocalDataStreamWithAliases]

  def allRemoteDataStreamsAndAliases: Task[Set[FullRemoteDataStreamWithAliases]]

  def allTemplates: Set[Template]

  def allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]]

  lazy val legacyTemplates: Set[Template.LegacyTemplate] =
    allTemplates.collect { case t: Template.LegacyTemplate => t }

  lazy val indexTemplates: Set[Template.IndexTemplate] =
    allTemplates.collect { case t: Template.IndexTemplate => t }

  lazy val componentTemplates: Set[Template.ComponentTemplate] =
    allTemplates.collect { case t: Template.ComponentTemplate => t }

  lazy val isReadOnlyRequest: Boolean =
    RequestContext.readActionPatternsMatcher.`match`(action)

  def isCompositeRequest: Boolean

  def isAllowedForDLS: Boolean

  def generalAuditEvents: JSONObject = new JSONObject()

}

object RequestContext extends Logging {

  type Aux[B <: BlockContext] = RequestContext { type BLOCK_CONTEXT = B }

  final case class Id private(value: String) {
    def toRequestId: RequestId = RequestId(value)
  }
  object Id {
    def fromString(value: String): Id = Id(value)
    
    def from(sessionCorrelationId: CorrelationId, requestId: String): Id =
      new Id(s"${sessionCorrelationId.value.value}-$requestId")
  }

  def show[B <: BlockContext](userMetadata: UserMetadata,
                              history: Vector[Block.History[B]])
                             (implicit headerShow: Show[Header]): Show[RequestContext.Aux[B]] =
    Show.show { r =>
      def stringifyUser = {
        userMetadata.loggedUser match {
          case Some(DirectlyLoggedUser(user)) => s"${user.show}"
          case Some(ImpersonatedUser(user, impersonatedBy)) => s"${impersonatedBy.show} (as ${user.show})"
          // todo: better implementation needed
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

      def stringifyUserGroup = {
        userMetadata.currentGroupId match {
          case Some(groupId) => groupId.show
          case None => "<N/A>"
        }
      }

      s"""{
         | ID:${r.id.show},
         | TYP:${r.`type`.show},
         | CGR:${stringifyUserGroup.show},
         | USR:${stringifyUser.show},
         | BRS:${r.headers.exists(_.name === Header.Name.userAgent).show},
         | KDX:${userMetadata.kibanaIndex.map(_.show).getOrElse("null").show},
         | ACT:${r.action.show},
         | OA:${r.remoteAddress.map(_.show).getOrElse("null")},
         | XFF:${r.headers.find(_.name === Header.Name.xForwardedFor).map(_.value.show).getOrElse("null").show},
         | DA:${r.localAddress.show},
         | IDX:${stringifyIndices.show},
         | MET:${r.method.show},
         | PTH:${r.uriPath.show},
         | CNT:${stringifyContentLength.show},
         | HDR:${r.headers.show},
         | HIS:${history.map(h => historyShow(headerShow).show(h)).mkString(", ").show},
         | }""".oneLiner
    }

  val readActionPatternsMatcher: PatternsMatcher[Action] = PatternsMatcher.create {
    Set(
      "cluster:monitor/*",
      "cluster:*get*",
      "cluster:*search*",
      "cluster:admin/*/get",
      "cluster:admin/*/status",
      "indices:admin/*/get",
      "indices:admin/*/explain",
      "indices:admin/aliases/exists",
      "indices:admin/aliases/get",
      "indices:admin/exists*",
      "indices:admin/get*",
      "indices:admin/mappings/fields/get*",
      "indices:admin/mappings/get*",
      "indices:admin/refresh*",
      "indices:admin/types/exists",
      "indices:admin/validate/*",
      "indices:admin/template/get",
      "indices:data/read/*",
      "indices:monitor/*",
      "indices:admin/xpack/rollup/search",
      "indices:admin/resolve/index",
      "indices:admin/index_template/get"
    ).map(Action.apply)
  }

  final case class Method private(value: String) extends AnyVal

  object Method {
    val GET: Method = Method.fromStringUnsafe("GET")
    val POST: Method = Method.fromStringUnsafe("POST")
    val PUT: Method = Method.fromStringUnsafe("PUT")
    val DELETE: Method = Method.fromStringUnsafe("DELETE")
    val OPTIONS: Method = Method.fromStringUnsafe("OPTIONS")
    val HEAD: Method = Method.fromStringUnsafe("HEAD")

    def fromStringUnsafe(str: String): Method = new Method(str.toUpperCase)
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

  def basicAuth: Option[BasicAuth] = {
    requestContext
      .headers
      .to(LazyList)
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

  private def findHeader(name: Header.Name) =
    requestContext.headers.find(_.name === name)
}

object RequestContextOps {
  implicit def from(rc: RequestContext): RequestContextOps = new RequestContextOps(rc)

  sealed trait RequestGroup
  object RequestGroup {
    final case class AGroup(userGroup: GroupId) extends RequestGroup
    case object `N/A` extends RequestGroup

    implicit val show: Show[RequestGroup] = Show.show {
      case AGroup(group) => group.value.value
      case `N/A` => "N/A"
    }

    implicit class ToOption(val requestGroup: RequestGroup) extends AnyVal {
      def toOption: Option[GroupId] = requestGroup match {
        case AGroup(userGroup) => Some(userGroup)
        case `N/A` => None
      }
    }
  }
}
