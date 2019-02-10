package tech.beshu.ror.acl.blocks.rules

import cats.implicits._
import io.jsonwebtoken.{Claims, Jwts}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.aDomain._
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.definitions.JwtDef
import tech.beshu.ror.acl.blocks.definitions.JwtDef.SignatureCheckMethod._
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule, RuleResult}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.utils.ClaimsOps.ClaimSearchResult.{Found, NotFound}
import tech.beshu.ror.acl.utils.ClaimsOps._
import tech.beshu.ror.utils.SecureStringHasher

import scala.util.Try

class JwtAuthRule(val settings: JwtAuthRule.Settings)
  extends AuthenticationRule
    with AuthorizationRule
    with Logging {

  override val name: Rule.Name = ExternalAuthenticationRule.name

  private val parser = settings.jwt.checkMethod match {
    case NoCheck(_) => Jwts.parser
    case Hmac(rawKey) => Jwts.parser.setSigningKey(rawKey)
    case Rsa(pubKey) => Jwts.parser.setSigningKey(pubKey)
    case Ec(pubKey) => Jwts.parser.setSigningKey(pubKey)
  }

  private val hasher = new SecureStringHasher("sha256")

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task
    .unit
    .flatMap { _ =>
      jwtTokenFrom(requestContext) match {
        case None =>
          logger.debug(s"Authorization header '${settings.jwt.headerName.show}' is missing or does not contain a JWT token")
          Task.now(Rejected)
        case Some(token) =>
          process(token, blockContext)
      }
    }

  private def jwtTokenFrom(requestContext: RequestContext) = {
    settings.jwt.headerName match {
      case Header.Name.authorization =>
        requestContext
          .bearerToken
          .map(t => JwtToken(t.value))
      case customHeaderName =>
        requestContext
          .headers
          .find(_.name === customHeaderName)
          .map(h => JwtToken(h.value))
    }
  }

  private def process(token: JwtToken, blockContext: BlockContext) = {
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
                  .authenticate(User.Id(hasher.hash(token.value.value)), Secret(token.value.value))
                  .map(RuleResult.fromCondition(modifiedBlockContext)(_))
              case Hmac(_) | Rsa(_) | Ec(_) =>
                Task.now(Fulfilled(modifiedBlockContext))
            }
        }
    }
  }

  private def userAndGroupsFromJwtToken(token: JwtToken) = {
    claimsFrom(token).map { claims => (userIdFrom(claims), groupsFrom(claims)) }
  }

  private def claimsFrom(token: JwtToken) = {
    settings.jwt.checkMethod match {
      case NoCheck(_) =>
        token.value.value.split("\\.").toList match {
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
        Try(parser.parseClaimsJws(token.value.value).getBody).toEither.left.map {
          ex =>
            logger.error(s"JWT token '${token.show}' parsing error", ex)
            ()
        }
    }
  }

  private def userIdFrom(claims: Claims) = {
    settings.jwt.userClaim.map(claims.userIdClaim)
  }

  private def groupsFrom(claims: Claims) = {
    settings.jwt.groupsClaim.map(claims.groupsClaim)
  }

  private def handleUserClaimSearchResult(blockContext: BlockContext, result: Option[ClaimSearchResult[User.Id]]) = {
    result match {
      case None => Right(blockContext)
      case Some(Found(userId)) => Right(blockContext.withLoggedUser(LoggedUser(userId)))
      case Some(NotFound) => Left(())
    }
  }

  private def handleGroupsClaimSearchResult(result: Option[ClaimSearchResult[Set[Group]]]) = {
    result match {
      case Some(NotFound) => Left(())
      case Some(Found(groups)) if settings.groups.nonEmpty =>
        if (groups.intersect(settings.groups).isEmpty) Left(())
        else Right(())
      case None if settings.groups.nonEmpty => Left(())
      case Some(Found(_)) | None => Right(())
    }
  }

}

object JwtAuthRule {
  val name = Rule.Name("jwt_auth")

  final case class Settings(jwt: JwtDef, groups: Set[Group])

}