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
package tech.beshu.ror.accesscontrol.blocks.rules.auth.base

import cats.implicits.toShow
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.SignatureCheckMethod.{Ec, Hmac, NoCheck, Rsa}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseJwtRule.*
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContextOps.from
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RefinedUtils.nes
import tech.beshu.ror.utils.uniquelist.UniqueList

import scala.util.Try

trait JwtRule extends Rule

trait BaseJwtRule extends Logging {

  protected def processUsingJwtToken[B <: BlockContext](blockContext: B,
                                                        jwt: JwtDef)
                                                       (operation: JwtData => Either[Unit, B]): Task[RuleResult[B]] = {
    implicit val jwtImpl: JwtDef = jwt
    jwtTokenFrom(blockContext.requestContext) match {
      case None =>
        logger.debug(s"[${blockContext.requestContext.id.show}] Authorization header '${jwt.authorizationTokenDef.headerName.show}' is missing or does not contain a JWT token")
        Task.now(Rejected())
      case Some(token) =>
        implicit val requestId: RequestId = blockContext.requestContext.id.toRequestId
        userAndGroupsFromJwtToken(token) match {
          case Left(_) =>
            Task.now(Rejected())
          case Right(jwtData) =>
            if (logger.delegate.isDebugEnabled) {
              logClaimSearchResults(jwtData.userId, jwtData.groups)
            }
            val claimProcessingResult = operation(jwtData)
            claimProcessingResult match {
              case Left(_) =>
                Task.now(Rejected())
              case Right(modifiedBlockContext) =>
                jwt.checkMethod match {
                  case NoCheck(service) =>
                    service
                      .authenticate(Credentials(User.Id(nes("jwt")), PlainTextSecret(token.value)))
                      .map(RuleResult.resultBasedOnCondition(modifiedBlockContext)(_))
                  case Hmac(_) | Rsa(_) | Ec(_) =>
                    Task.now(Fulfilled(modifiedBlockContext))
                }
            }
        }
    }
  }

  protected def finalize[B <: BlockContext](result: RuleResult[B],
                                            jwt: JwtDef): Task[RuleResult[B]] = {
    implicit val jwtImpl: JwtDef = jwt
    result match {
      case rejected: Rejected[B] =>
        Task.now(rejected)
      case Fulfilled(blockContext) =>
        jwtTokenFrom(blockContext.requestContext) match {
          case None =>
            Task.now(Rejected())
          case Some(token) =>
            implicit val requestId: RequestId = blockContext.requestContext.id.toRequestId
            jwt.checkMethod match {
              case NoCheck(service) =>
                service
                  .authenticate(Credentials(User.Id(nes("jwt")), PlainTextSecret(token.value)))
                  .map(RuleResult.resultBasedOnCondition(blockContext)(_))
              case Hmac(_) | Rsa(_) | Ec(_) =>
                Task.now(Fulfilled(blockContext))
            }
        }
    }
  }

  private def jwtTokenFrom(requestContext: RequestContext)(implicit jwt: JwtDef) = {
    requestContext
      .authorizationToken(jwt.authorizationTokenDef)
      .map(t => Jwt.Token(t.value))
  }

  private def logClaimSearchResults(user: Option[ClaimSearchResult[User.Id]],
                                    groups: Option[ClaimSearchResult[UniqueList[Group]]])
                                   (implicit requestId: RequestId, jwt: JwtDef): Unit = {
    (jwt.userClaim, user) match {
      case (Some(userClaim), Some(u)) =>
        logger.debug(s"[${requestId.show}] JWT resolved user for claim ${userClaim.name.rawPath}: ${u.show}")
      case _ =>
    }
    (jwt.groupsConfig, groups) match {
      case (Some(groupsConfig), Some(g)) =>
        val claimsDescription = groupsConfig.namesClaim match {
          case Some(namesClaim) => s"claims (id:'${groupsConfig.idsClaim.name.show}',name:'${namesClaim.name.show}')"
          case None => s"claim '${groupsConfig.idsClaim.name.show}'"
        }
        logger.debug(s"[${requestId.show}] JWT resolved groups for ${claimsDescription.show}: ${g.show}")
      case _ =>
    }
  }

  private def userAndGroupsFromJwtToken(token: Jwt.Token)
                                       (implicit requestId: RequestId,
                                        jwt: JwtDef): Either[Unit, JwtData] = {
    claimsFrom(token).map { decodedJwtToken =>
      JwtData(decodedJwtToken, userIdFrom(decodedJwtToken), groupsFrom(decodedJwtToken))
    }
  }

  private def logBadToken(ex: Throwable, token: Jwt.Token)
                         (implicit requestId: RequestId): Unit = {
    val tokenParts = token.show.split("\\.")
    val printableToken = if (!logger.delegate.isDebugEnabled && tokenParts.length === 3) {
      // signed JWT, last block is the cryptographic digest, which should be treated as a secret.
      s"${tokenParts(0)}.${tokenParts(1)} (omitted digest)"
    }
    else {
      token.show
    }
    logger.debug(s"[${requestId.show}] JWT token '${printableToken.show}' parsing error: ${ex.getClass.getSimpleName.show} ${ex.getMessage.show}")
  }

  private def claimsFrom(token: Jwt.Token)
                        (implicit requestId: RequestId,
                         jwt: JwtDef) = {
    val parser = jwt.checkMethod match {
      case NoCheck(_) => Jwts.parser().unsecured().build()
      case Hmac(rawKey) => Jwts.parser().verifyWith(Keys.hmacShaKeyFor(rawKey)).build()
      case Rsa(pubKey) => Jwts.parser().verifyWith(pubKey).build()
      case Ec(pubKey) => Jwts.parser().verifyWith(pubKey).build()
    }
    jwt.checkMethod match {
      case NoCheck(_) =>
        token.value.value.split("\\.").toList match {
          case fst :: snd :: _ =>
            Try(parser.parseUnsecuredClaims(s"$fst.$snd.").getPayload)
              .toEither
              .map(Jwt.Payload.apply)
              .left.map { ex => logBadToken(ex, token) }
          case _ =>
            Left(())
        }
      case Hmac(_) | Rsa(_) | Ec(_) =>
        Try(parser.parseSignedClaims(token.value.value).getPayload)
          .toEither
          .map(Jwt.Payload.apply)
          .left.map { ex => logBadToken(ex, token) }
    }
  }

  private def userIdFrom(payload: Jwt.Payload)(implicit jwt: JwtDef): Option[ClaimSearchResult[User.Id]] = {
    jwt.userClaim.map(payload.claims.userIdClaim)
  }

  private def groupsFrom(payload: Jwt.Payload)(implicit jwt: JwtDef): Option[ClaimSearchResult[UniqueList[Group]]] = {
    jwt.groupsConfig.map(groupsConfig =>
      payload.claims.groupsClaim(groupsConfig.idsClaim, groupsConfig.namesClaim)
    )
  }
}

object BaseJwtRule {

  protected final case class JwtData(payload: Jwt.Payload,
                                     userId: Option[ClaimSearchResult[User.Id]],
                                     groups: Option[ClaimSearchResult[UniqueList[Group]]])

}
