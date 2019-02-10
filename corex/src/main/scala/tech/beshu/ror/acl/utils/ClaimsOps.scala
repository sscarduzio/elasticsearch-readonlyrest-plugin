package tech.beshu.ror.acl.utils

import java.util

import eu.timepit.refined.types.string.NonEmptyString
import io.jsonwebtoken.Claims
import tech.beshu.ror.acl.aDomain.{ClaimName, Group, User}
import tech.beshu.ror.acl.utils.ClaimsOps.ClaimSearchResult
import tech.beshu.ror.acl.utils.ClaimsOps.ClaimSearchResult._

import scala.collection.JavaConverters._
import scala.language.implicitConversions

class ClaimsOps(val claims: Claims) extends AnyVal {

  def userIdClaim(name: ClaimName): ClaimSearchResult[User.Id] = {
    Option(claims.get(name.value.value, classOf[String])) match {
      case Some(id) => Found(User.Id(id))
      case None => NotFound
    }
  }

  // todo: use json path (with jackson? or maybe we can convert java map to json?)
  def groupsClaim(name: ClaimName): ClaimSearchResult[Set[Group]] = {
    val result = name.value.value.split("[.]").toList match {
      case Nil | _ :: Nil => Option(claims.get(name.value.value, classOf[Object]))
      case path :: restPaths =>
        restPaths.foldLeft(Option(claims.get(path, classOf[Object]))) {
          case (None, _) => None
          case (Some(value), currentPath) =>
            value match {
              case map: util.Map[String, Object] =>
                Option(map.get(currentPath))
              case _ =>
                Some(value)
            }
        }
    }
    result match {
      case Some(value: String) =>
        Found(toGroup(value).map(Set(_)).getOrElse(Set.empty))
      case Some(values) if values.isInstanceOf[util.Collection[String]] =>
        val collection = values.asInstanceOf[util.Collection[String]]
        Found(collection.asScala.toList.flatMap(toGroup).toSet)
      case _ =>
        NotFound
    }
  }

  private def toGroup(value: String) = {
    NonEmptyString.unapply(value).map(Group.apply)
  }
}

object ClaimsOps {
  implicit def toClaimsOps(claims: Claims): ClaimsOps = new ClaimsOps(claims)

  sealed trait ClaimSearchResult[+T]
  object ClaimSearchResult {
    final case class Found[+T](value: T) extends ClaimSearchResult[T]
    case object NotFound extends ClaimSearchResult[Nothing]
  }
}
