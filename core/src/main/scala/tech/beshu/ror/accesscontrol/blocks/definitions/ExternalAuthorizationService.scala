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
package tech.beshu.ror.accesscontrol.blocks.definitions

import cats.implicits._
import cats.{Eq, Show}
import com.softwaremill.sttp._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthorizationService.Name
import tech.beshu.ror.accesscontrol.blocks.definitions.HttpExternalAuthorizationService.AuthTokenSendMethod.{UsingHeader, UsingQueryParam}
import tech.beshu.ror.accesscontrol.blocks.definitions.HttpExternalAuthorizationService._
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions.Item
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CacheableAction
import tech.beshu.ror.com.jayway.jsonpath.JsonPath
import tech.beshu.ror.utils.uniquelist.UniqueList

import scala.jdk.CollectionConverters._
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

trait ExternalAuthorizationService extends Item {
  override type Id = Name
  def id: Id
  def grantsFor(userId: User.Id)
               (implicit requestId: RequestId): Task[UniqueList[Group]]
  def serviceTimeout: FiniteDuration Refined Positive

  override implicit def show: Show[Name] = Name.nameShow
}
object ExternalAuthorizationService {

  final case class Name(value: NonEmptyString)
  object Name {
    implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals
    implicit val nameShow: Show[Name] = Show.show(_.value.value)
  }
}

class HttpExternalAuthorizationService(override val id: ExternalAuthorizationService#Id,
                                       uri: Uri,
                                       method: SupportedHttpMethod,
                                       tokenName: AuthTokenName,
                                       groupsJsonPath: JsonPath,
                                       authTokenSendMethod: AuthTokenSendMethod,
                                       defaultHeaders: Set[Header],
                                       defaultQueryParams: Set[QueryParam],
                                       override val serviceTimeout: Refined[FiniteDuration, Positive],
                                       httpClient: HttpClient)
  extends ExternalAuthorizationService
  with Logging {

  override def grantsFor(userId: User.Id)
                        (implicit requestId: RequestId): Task[UniqueList[Group]] = {
    httpClient
      .send(createRequest(userId))
      .flatMap { response =>
        response.body match {
          case Right(body) =>
            Task.now(groupsFromResponseBody(body))
          case Left(_) =>
            Task.raiseError(InvalidResponse(s"Invalid response from external authorization service '${id.show}' - ${response.statusText}"))
        }
      }
  }

  private def createRequest(userId: User.Id) = {
    val uriWithParams = uri.params(queryParams(userId))
    method match {
      case SupportedHttpMethod.Get =>
        sttp
          .get(uriWithParams)
          .headers(headersMap(userId))
      case SupportedHttpMethod.Post =>
        sttp
          .post(uriWithParams)
          .headers(headersMap(userId))
    }
  }

  private def groupsFromResponseBody(body: String): UniqueList[Group] = {
    val groupsFromPath =
      Try(groupsJsonPath.read[java.util.List[String]](body))
        .map(
          _.asScala
            .flatMap(NonEmptyString.from(_).toOption)
            .map(GroupId.apply)
            .map(Group.from)
        )
    groupsFromPath match {
      case Success(groups) =>
        logger.debug(s"Groups returned by groups provider '${id.show}': ${groups.map(_.show).mkString(",")}")
        UniqueList.fromIterable(groups)
      case Failure(ex) =>
        logger.debug(s"Group based authorization response exception - provider '${id.show}'", ex)
        UniqueList.empty
    }
  }

  private def queryParams(userId: User.Id): Map[String, String] = {
    defaultQueryParams.map(p => (autoUnwrap(p.name), autoUnwrap(p.value))).toMap ++
      (authTokenSendMethod match {
        case UsingQueryParam => Map(tokenName.value.value -> userId.value.value)
        case UsingHeader => Map.empty[String, String]
      })
  }

  private def headersMap(userId: User.Id): Map[String, String] = {
    defaultHeaders.map(h => (h.name.value.value, h.value.value)).toMap ++
      (authTokenSendMethod match {
        case UsingHeader => Map(tokenName.value.value -> userId.value.value)
        case UsingQueryParam => Map.empty
      })
  }
}

object HttpExternalAuthorizationService {
  final case class QueryParam(name: NonEmptyString, value: NonEmptyString)
  final case class AuthTokenName(value: NonEmptyString)

  sealed trait AuthTokenSendMethod
  object AuthTokenSendMethod {
    case object UsingHeader extends AuthTokenSendMethod
    case object UsingQueryParam extends AuthTokenSendMethod
  }

  sealed trait SupportedHttpMethod
  object SupportedHttpMethod {
    case object Get extends SupportedHttpMethod
    case object Post extends SupportedHttpMethod
  }

  final case class InvalidResponse(message: String) extends Exception(message)
}

class CacheableExternalAuthorizationServiceDecorator(underlying: ExternalAuthorizationService,
                                                     ttl: FiniteDuration Refined Positive)
  extends ExternalAuthorizationService {

  private val cacheableGrantsFor = new CacheableAction[User.Id, UniqueList[Group]](ttl,
    (userId, requestId) => underlying.grantsFor(userId)(requestId))

  override val id: ExternalAuthorizationService#Id = underlying.id

  override def grantsFor(userId: User.Id)
                        (implicit requestId: RequestId): Task[UniqueList[Group]] =
    cacheableGrantsFor.call(userId, serviceTimeout)

  override def serviceTimeout: Refined[FiniteDuration, Positive] =
    underlying.serviceTimeout
}