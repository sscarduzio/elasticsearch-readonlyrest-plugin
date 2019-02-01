package tech.beshu.ror.acl.blocks.rules

import java.util

import cats.data._
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import io.jsonwebtoken.{Claims, Jwts}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.aDomain.{Group, LoggedUser, Secret, User}
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.definitions.JwtDef
import tech.beshu.ror.acl.blocks.definitions.JwtDef.SignatureCheckMethod._
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule, RuleResult}
import tech.beshu.ror.acl.orders.groupOrder
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._
import tech.beshu.ror.commons.utils.SecureStringHasher

import scala.collection.JavaConverters._
import scala.collection.SortedSet
import scala.util.Try


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

  private val hasher = new SecureStringHasher("sha256")

  override val name: Rule.Name = ExternalAuthenticationRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task
    .unit
    .flatMap { _ =>
      requestContext.bearerToken(settings.jwt.headerName) match {
        case None =>
          logger.debug("Authorization header is missing or does not contain a bearer token")
          Task.now(Rejected)
        case Some(token) =>
          process(token, blockContext)
      }
    }

  private def process(token: BearerToken, blockContext: BlockContext) = {
    userAndGroupsFromJwtToken(token) match {
      case Left(_) =>
        Task.now(Rejected)
      case Right((user, groups)) =>
        val claimProcessingResult = for {
          newBlockContext <- handleUserClaimSearchResult(blockContext, user)
          _ <- handleGroupsClaimSearchResult(groups)
        } yield newBlockContext
        claimProcessingResult match {
          case Left(_) =>
            Task.now(Rejected)
          case Right(modifiedBlockContext) =>
            settings.jwt.checkMethod match {
              case NoCheck(service) =>
                service
                  .authenticate(User.Id(hasher.hash(token.value)), Secret(token.value))
                  .map(RuleResult.fromCondition(modifiedBlockContext)(_))
              case Hmac(_) | Rsa(_) | Ec(_) =>
                Task.now(Fulfilled(modifiedBlockContext))
            }
        }
    }
  }

  private def handleUserClaimSearchResult(blockContext: BlockContext, result: ClaimSearchResult[User.Id]) = {
    result match {
      case NotFound => Left(())
      case NotApplicable => Right(blockContext)
      case Found(userId) => Right(blockContext.withLoggedUser(LoggedUser(userId)))
    }
  }

  private def handleGroupsClaimSearchResult(result: ClaimSearchResult[NonEmptySet[Group]]) = {
    result match {
      case NotFound => Left(())
      case Found(groups) if settings.groups.nonEmpty =>
        if (groups.toSortedSet.intersect(settings.groups).isEmpty) Left(())
        else Right(())
      case NotApplicable if settings.groups.nonEmpty => Left(())
      case Found(_) | NotApplicable => Right(())
    }
  }

  private def userAndGroupsFromJwtToken(token: BearerToken) = {
    claimsFrom(token).map { claims => (userIdFrom(claims), groupsFrom(claims)) }
  }

  private def claimsFrom(token: BearerToken) = {
    settings.jwt.checkMethod match {
      case NoCheck(_) =>
        token.value.split("\\.").toList match {
          case fst :: snd :: _ =>
            Try(parser.parseClaimsJwt(s"$fst.$snd.").getBody).toEither.left.map {
              ex =>
                logger.error(s"JWT token '${token.show}' parsing error", ex)
                ()
            }
          case _ =>
            Left(())
        }
      case Hmac(_) | Rsa(_) | Ec(_) =>
        Try(parser.parseClaimsJws(token.value).getBody).toEither.left.map {
          ex =>
            logger.error(s"JWT token '${token.show}' parsing error", ex)
            ()
        }
    }
  }

  private def userIdFrom(claims: Claims): ClaimSearchResult[User.Id] = {
    settings.jwt.userClaim
      .map { name =>
        Option(claims.get(name.value, classOf[String])) match {
          case Some(id) => Found(User.Id(id))
          case None => NotFound
        }
      }
      .getOrElse(NotApplicable)
  }

  private def groupsFrom(claims: Claims) = {
    settings.jwt.groupsClaim
      .map { name =>
        name.value.split("[.]").toList match {
          case Nil | _ :: Nil => Option(claims.get(name.value, classOf[Object]))
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
      }
      .map {
        case Some(value: String) =>
          toGroup(value).map(NonEmptySet.one(_)).map(Found.apply).getOrElse(NotFound)
        case Some(values) if values.isInstanceOf[util.Collection[String]] =>
          val collection = values.asInstanceOf[util.Collection[String]]
          NonEmptySet
            .fromSet(SortedSet.empty[Group] ++ collection.asScala.toList.flatMap(toGroup).toSet)
            .map(Found.apply)
            .getOrElse(NotFound)
        case _ =>
          NotFound
      }
      .getOrElse(NotApplicable)
  }

  private def toGroup(value: String) = {
    NonEmptyString.unapply(value).map(Group.apply)
  }

  private trait ClaimSearchResult[+T]
  private final case class Found[+T](value: T) extends ClaimSearchResult[T]
  private case object NotFound extends ClaimSearchResult[Nothing]
  private case object NotApplicable extends ClaimSearchResult[Nothing]

}

object JwtAuthRule {
  val name = Rule.Name("jwt_auth")

  final case class Settings(jwt: JwtDef, groups: Set[Group])

}