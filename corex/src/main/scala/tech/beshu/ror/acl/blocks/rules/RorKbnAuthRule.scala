package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import io.jsonwebtoken.Jwts
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.aDomain._
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.definitions.RorKbnDef.SignatureCheckMethod.{Ec, Hmac, Rsa}
import tech.beshu.ror.acl.blocks.definitions.RorKbnDef
import tech.beshu.ror.acl.blocks.rules.RorKbnAuthRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule, RuleResult}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._
import tech.beshu.ror.acl.utils.ClaimsOps.ClaimSearchResult.{Found, NotFound}
import tech.beshu.ror.acl.utils.ClaimsOps._

import scala.util.Try

class RorKbnAuthRule(val settings: Settings)
  extends AuthenticationRule
    with AuthorizationRule
    with Logging {

  override val name: Rule.Name = RorKbnAuthRule.name

  private val parser = settings.rorKbn.checkMethod match {
    case Hmac(rawKey) => Jwts.parser.setSigningKey(rawKey)
    case Rsa(pubKey) => Jwts.parser.setSigningKey(pubKey)
    case Ec(pubKey) => Jwts.parser.setSigningKey(pubKey)
  }

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task {
    val authHeaderName = Header.Name.authorization
    requestContext.bearerToken(authHeaderName) match {
      case None =>
        logger.debug(s"Authorization header '${authHeaderName.show}' is missing or does not contain a bearer token")
        Rejected
      case Some(token) =>
        process(token, blockContext)
    }
  }

  private def process(token: BearerToken, blockContext: BlockContext) = {
    userAndGroupsFromJwtToken(token) match {
      case Left(_) =>
        Rejected
      case Right((user, groups)) =>
        val claimProcessingResult = for {
          newBlockContext <- handleUserClaimSearchResult(blockContext, user)
          _ <- handleGroupsClaimSearchResult(groups)
        } yield newBlockContext
        claimProcessingResult match {
          case Left(_) =>
            Rejected
          case Right(modifiedBlockContext) =>
            Fulfilled(modifiedBlockContext)
        }
    }
  }

  private def userAndGroupsFromJwtToken(token: BearerToken) = {
    claimsFrom(token)
      .map { claims =>
        (claims.userIdClaim(RorKbnAuthRule.userClaimName), claims.groupsClaim(RorKbnAuthRule.groupsClaimName))
      }
  }

  private def claimsFrom(token: BearerToken) = {
    Try(parser.parseClaimsJws(token.value).getBody).toEither.left.map {
      ex =>
        logger.error(s"JWT token '${token.show}' parsing error", ex)
        ()
    }
  }

  private def handleUserClaimSearchResult(blockContext: BlockContext, result: ClaimSearchResult[User.Id]) = {
    result match {
      case Found(userId) => Right(blockContext.withLoggedUser(LoggedUser(userId)))
      case NotFound => Left(())
    }
  }

  private def handleGroupsClaimSearchResult(result: ClaimSearchResult[NonEmptySet[Group]]) = {
    result match {
      case NotFound => Left(())
      case Found(groups) if settings.groups.nonEmpty =>
        if (groups.toSortedSet.intersect(settings.groups).isEmpty) Left(())
        else Right(())
      case Found(_) => Right(())
    }
  }

}

object RorKbnAuthRule {
  val name = Rule.Name("ror_kbn_auth")

  final case class Settings(rorKbn: RorKbnDef, groups: Set[Group])

  private val userClaimName = ClaimName(NonEmptyString.unsafeFrom("user"))
  private val groupsClaimName = ClaimName(NonEmptyString.unsafeFrom("groups"))
}
