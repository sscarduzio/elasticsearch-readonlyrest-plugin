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
import sttp.model.Uri
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthorizationService.Name
import tech.beshu.ror.accesscontrol.blocks.definitions.HttpExternalAuthorizationService.Config.AuthTokenSendMethod.{UsingHeader, UsingQueryParam}
import tech.beshu.ror.accesscontrol.blocks.definitions.HttpExternalAuthorizationService.Config._
import tech.beshu.ror.accesscontrol.blocks.definitions.HttpExternalAuthorizationService._
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions.Item
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CacheableAction
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.json.JsonPath
import tech.beshu.ror.utils.uniquelist.UniqueList

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

trait ExternalAuthorizationService extends Item {
  override type Id = Name
  def id: Id
  def grantsFor(userId: User.Id)
               (implicit requestId: RequestId): Task[UniqueList[Group]]
  def serviceTimeout: PositiveFiniteDuration

  override implicit def show: Show[Name] = Name.nameShow
}
object ExternalAuthorizationService {

  final case class Name(value: NonEmptyString)
  object Name {
    implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals
    implicit val nameShow: Show[Name] = Show.show(_.value.value)
  }
}

final class HttpExternalAuthorizationService(override val id: ExternalAuthorizationService#Id,
                                             override val serviceTimeout: PositiveFiniteDuration,
                                             val config: HttpExternalAuthorizationService.Config,
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
    val uriWithParams = config.uri.addParams(queryParams(userId))
    config.method match {
      case SupportedHttpMethod.Get =>
        sttp.client3.basicRequest
          .get(uriWithParams)
          .headers(headersMap(userId))
      case SupportedHttpMethod.Post =>
        sttp.client3.basicRequest
          .post(uriWithParams)
          .headers(headersMap(userId))
    }
  }

  private def queryParams(userId: User.Id): Map[String, String] = {
    config.defaultQueryParams.map(p => (autoUnwrap(p.name), autoUnwrap(p.value))).toMap ++
      (config.authTokenSendMethod match {
        case UsingQueryParam => Map(config.tokenName.value.value -> userId.value.value)
        case UsingHeader => Map.empty[String, String]
      })
  }

  private def headersMap(userId: User.Id): Map[String, String] = {
    config.defaultHeaders.map(h => (h.name.value.value, h.value.value)).toMap ++
      (config.authTokenSendMethod match {
        case UsingHeader => Map(config.tokenName.value.value -> userId.value.value)
        case UsingQueryParam => Map.empty
      })
  }

  private def groupsFromResponseBody(body: String): UniqueList[Group] = {
    val groupsFromBody = groupsFrom(body)
    groupsFromBody match {
      case Success(groups) =>
        logger.debug(s"Groups returned by groups provider '${id.show}': ${groups.map(_.show).mkString(",")}")
        UniqueList.fromIterable(groups)
      case Failure(ex) =>
        logger.debug(s"Group based authorization response exception - provider '${id.show}'", ex)
        UniqueList.empty
    }
  }

  private def groupsFrom(body: String): Try[List[Group]] = {
    for {
      rawGroupIds <- groupIdsFrom(body)
      groups <- groupsFrom(body, rawGroupIds)
    } yield groups
  }

  private def groupIdsFrom(body: String) = {
    config.groupsConfig.idsConfig.jsonPath.read[java.util.List[String]](body)
      .map {
        _.asScala.toList
      }
  }

  private def groupsFrom(body: String, rawGroupIds: List[String]): Try[List[Group]] = {
    config.groupsConfig.namesConfig match {
      case Some(namesConfig) =>
        groupNamesFrom(body, namesConfig)
          .flatMap {
            case rawGroupNames if rawGroupNames.size == rawGroupIds.size =>
              Success(formGroups(groupIdsWithNames = rawGroupIds.zip(rawGroupNames)))
            case rawGroupNames =>
              Failure(new IllegalArgumentException(
                s"Group names array extracted from the response at json path ${namesConfig.jsonPath.rawPath} has different size [size=${rawGroupNames.size}] than " +
                  s"the group IDs array extracted from the response at json path ${config.groupsConfig.idsConfig.jsonPath.rawPath} [size=${rawGroupIds.size}]"
              ))
          }
      case None =>
        Success(
          rawGroupIds
            .flatMap(toGroupId)
            .map(Group.from)
        )
    }
  }

  private def groupNamesFrom(body: String, namesConfig: GroupsConfig.GroupNamesConfig): Try[List[String]] = {
    namesConfig.jsonPath.read[java.util.List[String]](body)
      .map {
        _.asScala.toList
      }
  }

  private def formGroups(groupIdsWithNames: List[(String, String)]) = {
    groupIdsWithNames.flatMap { case (groupId, groupName) =>
      toGroupId(groupId)
        .map(id => Group(id, toGroupName(value = groupName, fallback = GroupName.from(id))))
    }
  }

  private def toGroupId(value: String): Option[GroupId] = NonEmptyString.unapply(value).map(GroupId.apply)

  private def toGroupName(value: String, fallback: GroupName) =
    NonEmptyString
      .unapply(value)
      .map(GroupName.apply)
      .getOrElse(fallback)
}

object HttpExternalAuthorizationService {

  import Config.*

  final case class Config(uri: Uri,
                          method: SupportedHttpMethod,
                          tokenName: AuthTokenName,
                          groupsConfig: GroupsConfig,
                          authTokenSendMethod: AuthTokenSendMethod,
                          defaultHeaders: Set[Header],
                          defaultQueryParams: Set[QueryParam])

  object Config {
    sealed trait SupportedHttpMethod
    object SupportedHttpMethod {
      case object Get extends SupportedHttpMethod
      case object Post extends SupportedHttpMethod
    }

    final case class AuthTokenName(value: NonEmptyString)

    final case class GroupsConfig(idsConfig: GroupsConfig.GroupIdsConfig, namesConfig: Option[GroupsConfig.GroupNamesConfig])
    object GroupsConfig {
      final case class GroupIdsConfig(jsonPath: JsonPath)
      final case class GroupNamesConfig(jsonPath: JsonPath)
    }

    final case class QueryParam(name: NonEmptyString, value: NonEmptyString)

    sealed trait AuthTokenSendMethod
    object AuthTokenSendMethod {
      case object UsingHeader extends AuthTokenSendMethod
      case object UsingQueryParam extends AuthTokenSendMethod
    }
  }

  final case class InvalidResponse(message: String) extends Exception(message)
}

final class CacheableExternalAuthorizationServiceDecorator(val underlying: ExternalAuthorizationService,
                                                           val ttl: PositiveFiniteDuration)
  extends ExternalAuthorizationService {

  private val cacheableGrantsFor = new CacheableAction[User.Id, UniqueList[Group]](ttl,
    (userId, requestId) => underlying.grantsFor(userId)(requestId))

  override val id: ExternalAuthorizationService#Id = underlying.id

  override def grantsFor(userId: User.Id)
                        (implicit requestId: RequestId): Task[UniqueList[Group]] =
    cacheableGrantsFor.call(userId, serviceTimeout)

  override def serviceTimeout: PositiveFiniteDuration =
    underlying.serviceTimeout
}