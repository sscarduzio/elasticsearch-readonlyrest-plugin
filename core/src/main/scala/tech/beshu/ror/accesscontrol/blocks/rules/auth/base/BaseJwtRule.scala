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
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.AuthenticationFailed
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, Decision}
import tech.beshu.ror.accesscontrol.blocks.Decision.{Denied, Permitted}
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.SignatureCheckMethod.{Ec, Hmac, NoCheck, Rsa}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.request.RequestContextOps.from
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RefinedUtils.nes
import tech.beshu.ror.utils.RequestIdAwareLogging

import scala.util.Try

trait BaseJwtRule extends RequestIdAwareLogging {

  protected def doPostAuthAction[
    B <: BlockContext, JWT_DEF <: JwtDef
  ](blockContext: B, jwt: JWT_DEF, denialCause: => Denied.Cause): Task[Decision[B]] = {
    implicit val requestId: RequestId = blockContext.requestContext.id.toRequestId
    jwt.checkMethod match {
      case NoCheck(service) =>
        jwtTokenFrom(blockContext, jwt, denialCause) match {
          case Denied(cause) =>
            Task.now(Denied(cause))
          case Permitted(token) =>
            service
              .authenticate(Credentials(User.Id(nes("jwt")), PlainTextSecret(token.value)))
              .map {
                case Right(_) => Permitted(blockContext)
                case Left(AuthenticationFailed) => Denied(denialCause)
              }
        }
      case _ =>
        Task.now(Permitted(blockContext))
    }
  }

  protected def processUsingJwtToken[
    B <: BlockContext, JWT_DEF <: JwtDef
  ](blockContext: B, jwt: JWT_DEF, denialCause: => Denied.Cause)
   (operation: Jwt.Payload => Decision[B]): Task[Decision[B]] = Task.delay {
    implicit val requestId: RequestId = blockContext.requestContext.id.toRequestId
    for {
      token <- jwtTokenFrom(blockContext, jwt, denialCause)
      jwtPayload <- claimsFrom(token, jwt, denialCause)
      claimProcessingResult <- operation(jwtPayload)
    } yield claimProcessingResult
  }

  private def jwtTokenFrom[
    B <: BlockContext, JWT_DEF <: JwtDef
  ](blockContext: B, jwt: JWT_DEF, denialCause: => Denied.Cause): Decision[Jwt.Token] = {
    implicit val blockContextImpl: B = blockContext
    blockContext.requestContext.authorizationToken(jwt.authorizationTokenDef) match {
      case Some(t) =>
        Decision.Permitted(Jwt.Token(t.value))
      case None =>
        logger.debug(s"Authorization header '${jwt.authorizationTokenDef.headerName.show}' is missing or does not contain a JWT token")
        Decision.Denied(denialCause)
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

  private def claimsFrom[JWT_DEF <: JwtDef](token: Jwt.Token, jwt: JWT_DEF, denialCause: => Denied.Cause)
                                           (implicit requestId: RequestId): Decision[Jwt.Payload] = {
    val parser = jwt.checkMethod match {
      case NoCheck(_) => Jwts.parser().unsecured().build()
      case Hmac(rawKey) => Jwts.parser().verifyWith(Keys.hmacShaKeyFor(rawKey)).build()
      case Rsa(pubKey) => Jwts.parser().verifyWith(pubKey).build()
      case Ec(pubKey) => Jwts.parser().verifyWith(pubKey).build()
    }

    def deny(ex: Throwable): Decision.Denied[Jwt.Payload] = {
      logBadToken(ex, token)
      Decision.Denied(denialCause)
    }

    jwt.checkMethod match {
      case NoCheck(_) =>
        token.value.value.split("\\.").toList match {
          case fst :: snd :: _ =>
            Try(parser.parseUnsecuredClaims(s"$fst.$snd.").getPayload)
              .toEither
              .fold(deny, claims => Decision.Permitted(Jwt.Payload(claims)))
          case _ =>
            Decision.Denied(denialCause)
        }
      case Hmac(_) | Rsa(_) | Ec(_) =>
        Try(parser.parseSignedClaims(token.value.value).getPayload)
          .toEither
          .map(Jwt.Payload.apply)
          .fold(deny, Decision.Permitted(_))
    }
  }

}
