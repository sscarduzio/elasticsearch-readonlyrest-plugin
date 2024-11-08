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
package tech.beshu.ror.accesscontrol.utils

import cats.Show
import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import io.jsonwebtoken.Claims
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult.*
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.{ClaimSearchResult, CustomClaimValue}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.uniquelist.UniqueList

import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions
import scala.util.{Success, Try}

class ClaimsOps(val claims: Claims) extends Logging {

  def headerNameClaim(name: Header.Name): ClaimSearchResult[Header] = {
    Option(claims.get(name.value.value, classOf[String]))
      .flatMap(NonEmptyString.unapply) match {
      case Some(headerValue) => Found(new Header(name, headerValue))
      case None => NotFound
    }
  }

  def customClaim(claimName: Jwt.ClaimName): ClaimSearchResult[CustomClaimValue] = {
    claimName.name.read[Any](claims)
      .map {
        case value: String =>
          Found(CustomClaimValue.SingleValue(value))
        case collection: java.util.Collection[_] =>
          val items = collection.asScala
            .collect {
              case value: String => value
              case value: Long => value.toString
            }
            .toList
          NonEmptyList.fromList(items) match {
            case Some(nel) => Found(CustomClaimValue.CollectionValue(nel))
            case None => NotFound
          }
        case _ =>
          NotFound
      }
      .fold(_ => NotFound, identity)
  }

  def userIdClaim(claimName: Jwt.ClaimName): ClaimSearchResult[User.Id] = {
    claimName.name.read[Any](claims)
      .map {
        case value: String =>
          NonEmptyString.from(value) match {
            case Left(_) => NotFound
            case Right(userStr) => Found(User.Id(userStr))
          }
        case _ => NotFound
      }
      .fold(_ => NotFound, identity)
  }

  def groupsClaim(groupIdsClaimName: Jwt.ClaimName,
                  groupNamesClaimName: Option[Jwt.ClaimName]): ClaimSearchResult[UniqueList[Group]] = {

    (for {
      groupIds <- readGroupIds(groupIdsClaimName)
      groupNames <- readGroupNames(groupNamesClaimName)
      searchResult = groupIds match {
        case Found(ids) => ClaimSearchResult.Found {
          groupNames match {
            case Found(names) if names.size == ids.size =>
              val idsWithNames = ids.zip(names)
              createGroupsFrom(idsWithNames = idsWithNames)
            case Found(names) =>
              logger.debug(
                s"Group names array extracted from the JWT at json path '${groupNamesClaimName.map(_.name.show).getOrElse("")}' has different size [size=${names.size.show}] than " +
                  s"the group IDs array extracted from the JWT at json path '${groupIdsClaimName.name.show}' [size=${ids.size.show}]. " +
                  s"Both array's size has to be equal. Only group IDs will be used for further processing.."
              )
              createGroupsFromIds(ids)
            case ClaimSearchResult.NotFound =>
              createGroupsFromIds(ids)
          }
        }
        case ClaimSearchResult.NotFound =>
          ClaimSearchResult.NotFound
      }
    } yield searchResult)
      .fold(_ => NotFound, identity)
  }

  private def readGroupIds(claimName: Jwt.ClaimName) = {
    readStringLikeOrIterable(claimName)
  }

  private def readGroupNames(claimName: Option[Jwt.ClaimName]) = {
    claimName match {
      case Some(groupNamesClaimName) =>
        readStringLikeOrIterable(groupNamesClaimName)
          .recover {
            case _: Throwable => NotFound
          }
      case None =>
        Success(ClaimSearchResult.NotFound)
    }
  }

  private def readStringLikeOrIterable(claimName: Jwt.ClaimName): Try[ClaimSearchResult[Iterable[Any]]] = {
    claimName.name.read[Any](claims)
      .map {
        case value: String =>
          Found(List(value))
        case collection: java.util.Collection[_] =>
          Found(collection.asScala)
        case _ =>
          NotFound
      }
  }

  private def createGroupsFromIds(ids: Iterable[Any]): UniqueList[Group] = UniqueList.from {
    ids
      .flatMap(nonEmptyStringFrom)
      .map(GroupId.apply)
      .map(Group.from)
  }

  private def createGroupsFrom(idsWithNames: Iterable[(Any, Any)]): UniqueList[Group] = UniqueList.from {
    idsWithNames
      .flatMap { case (id, name) =>
        nonEmptyStringFrom(id)
          .map(GroupId.apply)
          .map { groupId =>
            val groupName = groupNameFrom(name, groupId)
            Group(groupId, groupName)
          }
      }
  }

  private def groupNameFrom(name: Any, groupId: GroupId) = {
    nonEmptyStringFrom(name)
      .map(GroupName.apply)
      .getOrElse {
        logger.debug(s"Unable to create a group name from '$name'. The group ID '${groupId.show}' will be used as a group name for further processing..")
        GroupName.from(groupId)
      }
  }

  private val nonEmptyStringFrom: Any => Option[NonEmptyString] = {
    case value: String => NonEmptyString.unapply(value)
    case value: Long => NonEmptyString.unapply(value.toString)
    case _ => None
  }
}

object ClaimsOps {
  implicit def toClaimsOps(claims: Claims): ClaimsOps = new ClaimsOps(claims)

  sealed trait ClaimSearchResult[+T]
  object ClaimSearchResult {
    final case class Found[+T](value: T) extends ClaimSearchResult[T]
    case object NotFound extends ClaimSearchResult[Nothing]

    implicit def show[T : Show]: Show[ClaimSearchResult[T]] = Show.show {
      case Found(value) => value.show
      case NotFound => "<Not Found>"
    }
  }

  sealed trait CustomClaimValue
  object CustomClaimValue {
    final case class SingleValue(value: String) extends CustomClaimValue
    final case class CollectionValue(value: NonEmptyList[String]) extends CustomClaimValue
  }
}
