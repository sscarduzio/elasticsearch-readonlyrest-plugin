package tech.beshu.ror.acl.blocks.rules

import java.util

import cats.implicits._
import cats.data._
import eu.timepit.refined.types.string.NonEmptyString
import io.jsonwebtoken.{Claims, Jwts}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.aDomain.{Group, User}
import tech.beshu.ror.acl.orders.groupOrder
import tech.beshu.ror.acl.blocks.definitions.JwtDef
import tech.beshu.ror.acl.blocks.definitions.JwtDef.SignatureCheckMethod._
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.Rejected
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule, RuleResult}
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._

import scala.collection.JavaConverters._
import scala.collection.SortedSet


/*
      JWT ALGO    FAMILY
      =======================
      NONE        None

      HS256       HMAC
      HS384       HMAC
      HS512       HMAC

      RS256       RSA
      RS384       RSA
      RS512       RSA
      PS256       RSA
      PS384       RSA
      PS512       RSA

      ES256       EC
      ES384       EC
      ES512       EC
    */

class JwtAuthRule(val settings: JwtAuthRule.Settings)
  extends AuthenticationRule
    with AuthorizationRule
    with Logging {

  private val parser = settings.jwt.checkMethod match {
    case NoCheck(_) => Jwts.parser
    case Hmac(rawKey) => Jwts.parser.setSigningKey(rawKey)
    case Rsa(pubKey) => Jwts.parser.setSigningKey(pubKey)
    case Ec(pubKey) => Jwts.parser.setSigningKey(pubKey)
  }

  override val name: Rule.Name = ExternalAuthenticationRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task {
    requestContext.bearerToken(settings.jwt.headerName) match {
      case None =>
        logger.debug("Authorization header is missing or does not contain a bearer token")
        Rejected
      case Some(token) =>
        process(token)
    }
  }

  private def process(token: BearerToken): RuleResult = ???

  private def userAndRolesFromJwtToken(token: BearerToken): Either[Unit, (Option[User.Id], Option[NonEmptySet[Group]])] = {
    claimsFrom(token).map { claims => (userIdFrom(claims), groupsFrom(claims)) }
  }

  private def claimsFrom(token: BearerToken) = {
    settings.jwt.checkMethod match {
      case NoCheck(_) =>
        token.value.split("\\.").toList match {
          case fst :: snd :: _ =>
            Right(parser.parseClaimsJwt(s"$fst.$snd.").getBody)
          case _ =>
            Left(())
        }
      case Hmac(_) | Rsa(_) | Ec(_) =>
        Right(parser.parseClaimsJws(token.value).getBody)
    }
  }

  private def userIdFrom(claims: Claims) = {
    settings.jwt.userClaim
      .flatMap(name => Option(claims.get(name.value, classOf[String])))
      .map(User.Id.apply)
  }

  private def groupsFrom(claims: Claims) = {
    settings.jwt.groupsClaim
      .map { name =>
        name.value.split("[.]").toList match {
          case Nil | _ :: Nil => claims.get(name.value, classOf[Any])
          case path :: restPaths =>
            restPaths.foldLeft(Option(claims.get(path, classOf[Any]))) {
              case (None, _) => None
              case (Some(value), currentPath) =>
                value match {
                  case map: Map[String, Any] => map.get(currentPath)
                  case _ => Some(value)
                }
            }
        }
      }
      .flatMap {
        case value: String =>
          toGroup(value).map(NonEmptySet.one(_))
        case values if values.isInstanceOf[util.Collection[String]] =>
          val collection = values.asInstanceOf[util.Collection[String]]
          NonEmptySet.fromSet(SortedSet.empty[Group] ++ collection.asScala.toList.flatMap(toGroup).toSet)
        case _ =>
          None
      }
  }

  private def toGroup(value: String) = {
    NonEmptyString.unapply(value).map(Group.apply)
  }

}

object JwtAuthRule {
  val name = Rule.Name("jwt_auth")

  final case class Settings(jwt: JwtDef, groups: Set[Value[Group]])

}