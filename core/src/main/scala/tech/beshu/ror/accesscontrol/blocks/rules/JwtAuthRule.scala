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
package tech.beshu.ror.accesscontrol.blocks.rules

import cats.implicits._
import eu.timepit.refined.auto._
import io.jsonwebtoken.Jwts
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.SignatureCheckMethod._
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule._
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.{AuthenticationImpersonationCustomSupport, AuthorizationImpersonationCustomSupport}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult.{Found, NotFound}
import tech.beshu.ror.accesscontrol.utils.ClaimsOps._
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.util.Try

final class JwtAuthRule(val settings: JwtAuthRule.Settings,
                        implicit override val caseMappingEquality: UserIdCaseMappingEquality)
  extends AuthRule
    with AuthenticationImpersonationCustomSupport
    with AuthorizationImpersonationCustomSupport
    with Logging {

  override val name: Rule.Name = JwtAuthRule.Name.name

  override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable

  private val parser =
    settings.jwt.checkMethod match {
      case NoCheck(_) => Jwts.parserBuilder().build()
      case Hmac(rawKey) => Jwts.parserBuilder().setSigningKey(rawKey).build()
      case Rsa(pubKey) => Jwts.parserBuilder().setSigningKey(pubKey).build()
      case Ec(pubKey) => Jwts.parserBuilder().setSigningKey(pubKey).build()
    }

  override protected[rules] def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task
    .unit
    .flatMap { _ =>
      jwtTokenFrom(blockContext.requestContext) match {
        case None =>
          logger.debug(s"Authorization header '${settings.jwt.authorizationTokenDef.headerName.show}' is missing or does not contain a JWT token")
          Task.now(Rejected())
        case Some(token) =>
          process(token, blockContext)
      }
    }

  override protected[rules] def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
    Task.now(RuleResult.Fulfilled(blockContext))

  private def jwtTokenFrom(requestContext: RequestContext) = {
    requestContext
      .authorizationToken(settings.jwt.authorizationTokenDef)
      .map(t => JwtToken(t.value))
  }

  private def process[B <: BlockContext : BlockContextUpdater](token: JwtToken,
                                                               blockContext: B): Task[RuleResult[B]] = {
    userAndGroupsFromJwtToken(token) match {
      case Left(_) =>
        Task.now(Rejected())
      case Right((tokenPayload, user, groups)) =>
        if (logger.delegate.isDebugEnabled) {
          logClaimSearchResults(user, groups)
        }
        val claimProcessingResult = for {
          newBlockContext <- handleUserClaimSearchResult(blockContext, user)
          finalBlockContext <- handleGroupsClaimSearchResult(newBlockContext, groups)
        } yield finalBlockContext.withUserMetadata(_.withJwtToken(tokenPayload))
        claimProcessingResult match {
          case Left(_) =>
            Task.now(Rejected())
          case Right(modifiedBlockContext) =>
            settings.jwt.checkMethod match {
              case NoCheck(service) =>
                service
                  .authenticate(Credentials(User.Id("jwt"), PlainTextSecret(token.value)))
                  .map(RuleResult.resultBasedOnCondition(modifiedBlockContext)(_))
              case Hmac(_) | Rsa(_) | Ec(_) =>
                Task.now(Fulfilled(modifiedBlockContext))
            }
        }
    }
  }

  private def logClaimSearchResults(user: Option[ClaimSearchResult[User.Id]],
                                    groups: Option[ClaimSearchResult[UniqueList[Group]]]): Unit = {
    (settings.jwt.userClaim, user) match {
      case (Some(userClaim), Some(u)) =>
        logger.debug(s"JWT resolved user for claim ${userClaim.name.getPath}: ${u.show}")
      case _ =>
    }
    (settings.jwt.groupsClaim, groups) match {
      case (Some(groupsClaim), Some(g)) =>
        logger.debug(s"JWT resolved groups for claim ${groupsClaim.name.getPath}: ${g.show}")
      case _ =>
    }
  }

  private def userAndGroupsFromJwtToken(token: JwtToken) = {
    claimsFrom(token).map { decodedJwtToken =>
      (decodedJwtToken, userIdFrom(decodedJwtToken), groupsFrom(decodedJwtToken))
    }
  }

  private def logBadToken(ex: Throwable, token: JwtToken): Unit = {
    val tokenParts = token.show.split("\\.")
    val printableToken = if (!logger.delegate.isDebugEnabled && tokenParts.length === 3) {
      // signed JWT, last block is the cryptographic digest, which should be treated as a secret.
      s"${tokenParts(0)}.${tokenParts(1)} (omitted digest)"
    }
    else {
      token.show
    }
    logger.debug(s"JWT token '$printableToken' parsing error: " + ex.getClass.getSimpleName + " " + ex.getMessage)
  }

  private def claimsFrom(token: JwtToken) = {
    settings.jwt.checkMethod match {
      case NoCheck(_) =>
        token.value.value.split("\\.").toList match {
          case fst :: snd :: _ =>
            Try(parser.parseClaimsJwt(s"$fst.$snd.").getBody)
              .toEither
              .map(JwtTokenPayload.apply)
              .left.map { ex => logBadToken(ex, token) }
          case _ =>
            Left(())
        }
      case Hmac(_) | Rsa(_) | Ec(_) =>
        Try(parser.parseClaimsJws(token.value.value).getBody)
          .toEither
          .map(JwtTokenPayload.apply)
          .left.map { ex => logBadToken(ex, token) }
    }
  }

  private def userIdFrom(payload: JwtTokenPayload) = {
    settings.jwt.userClaim.map(payload.claims.userIdClaim)
  }

  private def groupsFrom(payload: JwtTokenPayload) = {
    settings.jwt.groupsClaim.map(payload.claims.groupsClaim)
  }

  private def handleUserClaimSearchResult[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                                   result: Option[ClaimSearchResult[User.Id]]) = {
    result match {
      case None => Right(blockContext)
      case Some(Found(userId)) => Right(blockContext.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(userId))))
      case Some(NotFound) => Left(())
    }
  }

  private def handleGroupsClaimSearchResult[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                                     result: Option[ClaimSearchResult[UniqueList[Group]]]) = {
    result match {
      case None if settings.groups.nonEmpty => Left(())
      case Some(NotFound) if settings.groups.nonEmpty => Left(())
      case Some(NotFound) => Right(blockContext) // if groups field is not found, we treat this situation as same as empty groups would be passed
      case Some(Found(groups)) if settings.groups.nonEmpty =>
        UniqueNonEmptyList.fromSortedSet(settings.groups.intersect(groups)) match {
          case Some(matchedGroups) => Right(blockContext.withUserMetadata(_.addAvailableGroups(matchedGroups)))
          case None => Left(())
        }
      case Some(Found(_)) | None => Right(blockContext)
    }
  }
}

object JwtAuthRule {

  implicit case object Name extends RuleName[JwtAuthRule] {
    override val name = Rule.Name("jwt_auth")
  }

  final case class Settings(jwt: JwtDef, groups: UniqueList[Group])

}