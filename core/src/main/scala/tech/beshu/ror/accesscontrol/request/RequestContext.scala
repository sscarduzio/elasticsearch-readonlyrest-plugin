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
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenDef.AllowedPrefix
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenDef.AllowedPrefix.StrictlyDefined
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenPrefix.bearer
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.matchers.ActionMatchers
import tech.beshu.ror.accesscontrol.request.RequestContext.Id
import tech.beshu.ror.accesscontrol.request.RequestContext.AuthorizationTokenRetrievingError.{InvalidValue, MissingHeader}
import tech.beshu.ror.es.EsServices
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
    ActionMatchers.readActionPatternsMatcher.`match`(action)

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

    def bearerToken: Either[AuthorizationTokenRetrievingError, AuthorizationToken] = authorizationTokenBy(
      AuthorizationTokenDef(headerName = Header.Name.authorization, allowedPrefix = StrictlyDefined(bearer))
    )

    def authorizationTokenBy(config: AuthorizationTokenDef): Either[AuthorizationTokenRetrievingError, AuthorizationToken] = {
      for {
        tokenHeader <- findHeader(config.headerName).toRight(MissingHeader)
        authorizationToken <- AuthorizationToken.from(tokenHeader.value).toRight(InvalidValue)
        _ <- config.allowedPrefix match {
          case AllowedPrefix.Any => Right(())
          case AllowedPrefix.StrictlyDefined(prefix) if prefix === authorizationToken.prefix => Right(())
          case AllowedPrefix.StrictlyDefined(_) => Left(InvalidValue)
        }
      } yield authorizationToken

    }

    private def findHeader(name: Header.Name) =
      requestContext.restRequest.allHeaders.find(_.name === name)
  }

  sealed trait AuthorizationTokenRetrievingError
  object AuthorizationTokenRetrievingError {
    case object MissingHeader extends AuthorizationTokenRetrievingError
    case object InvalidValue extends AuthorizationTokenRetrievingError
  }
}
