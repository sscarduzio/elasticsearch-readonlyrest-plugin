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

import cats.Eval
import cats.implicits.*
import org.json.JSONObject
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.Action.RorAction
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenDef.AllowedPrefix
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenDef.AllowedPrefix.StrictlyDefined
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenPrefix.bearer
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext.Id
import tech.beshu.ror.es.EsServices
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.time.Instant
import scala.language.implicitConversions

trait BaseEsContext {
  def correlationId: Eval[CorrelationId]
  def esTaskId: Long
  def restRequest: RestRequest
}

trait RequestContext {

  type BLOCK_CONTEXT <: BlockContext

  def initialBlockContext(block: Block): BLOCK_CONTEXT

  def restRequest: RestRequest

  def timestamp: Instant

  def taskId: Long

  def id: Id

  def rorKibanaSessionId: CorrelationId

  def `type`: Type

  def action: Action

  def requestedIndices: Option[Set[RequestedIndex[ClusterIndexName]]]

  def indexAttributes: Set[IndexAttribute]

  def esServices: EsServices

  lazy val isReadOnlyRequest: Boolean =
    RequestContext.readActionPatternsMatcher.`match`(action)

  def isCompositeRequest: Boolean

  def isAllowedForDLS: Boolean

  def generalAuditEvents: JSONObject = new JSONObject()

  def currentGroupId: Option[GroupId] = {
    restRequest
      .allHeaders
      .find(_.name === Header.Name.currentGroup)
      .map(h => GroupId(h.value))
  }
}

object RequestContext extends RequestIdAwareLogging {

  type Aux[B <: BlockContext] = RequestContext {type BLOCK_CONTEXT = B}

  final case class Id private(value: String) {
    def toRequestId: RequestId = RequestId(value)
  }
  object Id {
    def fromString(value: String): Id = Id(value)

    def from(esContext: BaseEsContext): Id = {
      new Id(s"${esContext.correlationId.value.value.value}-${esContext.restRequest.hashCode()}#${esContext.esTaskId}")
    }
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

  sealed trait RequestGroup
  object RequestGroup {
    final case class AGroup(userGroup: GroupId) extends RequestGroup
    case object `N/A` extends RequestGroup

    implicit class ToOption(val requestGroup: RequestGroup) extends AnyVal {
      def toOption: Option[GroupId] = requestGroup match {
        case AGroup(userGroup) => Some(userGroup)
        case `N/A` => None
      }
    }
  }

  private val readActionPatternsMatcher: PatternsMatcher[Action] = PatternsMatcher.create {
    Set(
      RorAction.RorUserMetadataAction.value,
      "cluster:monitor/*",
      "cluster:*get*",
      "cluster:admin/*/get",
      "cluster:admin/*/status",
      "cluster:admin/*/verify",
      "cluster:admin/idp/saml/metadata",
      "cluster:admin/ingest/pipeline/simulate",
      "cluster:admin/slm/stats",
      "cluster:admin/transform/node_stats",
      "cluster:admin/transform/preview",
      "cluster:admin/xpack/application/*/get",
      "cluster:admin/xpack/application/search_application/list",
      "cluster:admin/xpack/application/search_application/render_query",
      "cluster:admin/xpack/connector/list",
      "cluster:admin/xpack/connector/sync_job/list",
      "cluster:admin/xpack/deprecation/info",
      "cluster:admin/xpack/deprecation/nodes/info",
      "cluster:admin/xpack/license/basic_status",
      "cluster:admin/xpack/license/trial_status",
      "cluster:admin/xpack/ml/data_frame/analytics/explain",
      "cluster:admin/xpack/ml/data_frame/analytics/preview",
      "cluster:admin/xpack/ml/datafeeds/preview",
      "cluster:admin/xpack/query_rules/list",
      "cluster:admin/xpack/security/api_key/query",
      "cluster:admin/xpack/security/user/list_privileges",
      "cluster:admin/xpack/searchable_snapshots/cache/stats",
      "cluster:admin/xpack/security/*/get",
      "cluster:admin/xpack/security/*/query",
      "cluster:monitor/async_search/status",
      "indices:admin/*/explain",
      "indices:admin/*/get",
      "indices:admin/aliases/exists",
      "indices:admin/aliases/get",
      "indices:admin/analyze",
      "indices:admin/exists*",
      "indices:admin/get*",
      "indices:admin/index_template/simulate",
      "indices:admin/index_template/simulate_index",
      "indices:admin/mappings/fields/get*",
      "indices:admin/mappings/get*",
      "indices:admin/migration/reindex_status",
      "indices:admin/refresh*",
      "indices:admin/search/search_shards",
      "indices:admin/template/get",
      "indices:admin/types/exists",
      "indices:admin/validate/*",
      "indices:data/read/*",
      "indices:admin/index_template/get",
      "indices:admin/resolve/*",
      "indices:admin/xpack/rollup/search",
      "indices:monitor/*",
    ).map(Action.apply)
  }

  extension (requestContext: RequestContext) {

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
      implicit val requestId: RequestId = requestContext.id.toRequestId
      requestContext
        .restRequest
        .allHeaders
        .to(LazyList)
        .map(BasicAuth.fromHeader)
        .find(_.isDefined)
        .flatten
    }

    def rawAuthHeader: Option[Header] = findHeader(Header.Name.authorization)

    def bearerToken: Option[AuthorizationToken] = authorizationTokenBy(
      AuthorizationTokenDef(headerName = Header.Name.authorization, allowedPrefix = StrictlyDefined(bearer))
    )

    def authorizationTokenBy(config: AuthorizationTokenDef): Option[AuthorizationToken] = {
      for {
        tokenHeader <- findHeader(config.headerName)
        authorizationToken <- AuthorizationToken.from(tokenHeader.value)
        _ <- config.allowedPrefix match {
          case AllowedPrefix.Any => Some(())
          case AllowedPrefix.StrictlyDefined(prefix) if prefix === authorizationToken.prefix => Some(())
          case AllowedPrefix.StrictlyDefined(_) => None
        }
      } yield authorizationToken

    }

    private def findHeader(name: Header.Name) =
      requestContext.restRequest.allHeaders.find(_.name === name)
  }
}
