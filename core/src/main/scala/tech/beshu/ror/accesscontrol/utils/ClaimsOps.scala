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

import cats.implicits._
import cats.Show
import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import io.jsonwebtoken.Claims
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName
import tech.beshu.ror.accesscontrol.domain.{Header, Jwt, User}
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult._
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.{ClaimSearchResult, CustomClaimValue}
import tech.beshu.ror.utils.uniquelist.UniqueList

import scala.jdk.CollectionConverters._
import scala.language.implicitConversions
import scala.util.Try

class ClaimsOps(val claims: Claims) extends Logging {

  def headerNameClaim(name: Header.Name): ClaimSearchResult[Header] = {
    Option(claims.get(name.value.value, classOf[String]))
      .flatMap(NonEmptyString.unapply) match {
      case Some(headerValue) => Found(new Header(name, headerValue))
      case None => NotFound
    }
  }

  def userIdClaim(claimName: Jwt.ClaimName): ClaimSearchResult[User.Id] = {
    Try(claimName.name.read[Any](claims))
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

  def groupsClaim(claimName: Jwt.ClaimName): ClaimSearchResult[UniqueList[GroupName]] = {
    Try(claimName.name.read[Any](claims))
      .map {
        case value: String =>
          Found(UniqueList.fromIterable((value :: Nil).flatMap(toGroup)))
        case collection: java.util.Collection[_] =>
          Found {
            UniqueList.fromIterable {
              collection.asScala
                .collect {
                  case value: String => value
                  case value: Long => value.toString
                }
                .flatMap(toGroup)
            }
          }
        case _ =>
          NotFound
      }
      .fold(_ => NotFound, identity)
  }

  def customClaim(claimName: Jwt.ClaimName): ClaimSearchResult[CustomClaimValue] = {
    Try(claimName.name.read[Any](claims))
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

  private def toGroup(value: String) = {
    NonEmptyString.unapply(value).map(GroupName.apply)
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
