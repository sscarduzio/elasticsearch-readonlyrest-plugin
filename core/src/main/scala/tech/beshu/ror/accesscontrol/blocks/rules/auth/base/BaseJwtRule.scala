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

import cats.data.EitherT
import cats.implicits.toShow
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.AuthenticationFailed
import tech.beshu.ror.accesscontrol.blocks.Decision.{Denied, Permitted}
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.SignatureCheckMethod.{Ec, Hmac, NoCheck, Rsa}
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, JwtDef}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, Decision}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.request.RequestContextOps.from
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RefinedUtils.nes
import tech.beshu.ror.utils.RequestIdAwareLogging

import scala.util.Try

trait BaseJwtRule extends RequestIdAwareLogging {

  // todo: in authz rule only we shouldn't have authentication failure cause, right?
  protected def doPostAuthAction[
    B <: BlockContext, JWT_DEF <: JwtDef
  ](blockContext: B, jwt: JWT_DEF): Task[Decision[B]] = {
    implicit val requestId: RequestId = blockContext.requestContext.id.toRequestId
    jwt.checkMethod match {
      case NoCheck(service) =>
        val result = for {
          token <- EitherT.fromEither[Task](extractJwtTokenFromHeader(blockContext, jwt))
          _ <- checkAuthenticationTokenValidity(service, token)
        } yield blockContext
        result
          .value
          .map {
            case Right(blockContext) => Permitted(blockContext)
            case Left(cause) => Denied(cause)
          }
      case _ =>
        Task.now(Permitted(blockContext))
    }
  }

  // todo: in authz rule only we shouldn't have authentication failure cause, right?
  protected def processUsingJwtToken[
    B <: BlockContext, JWT_DEF <: JwtDef
  ](blockContext: B, jwt: JWT_DEF)
   (operation: Jwt.Payload => Either[Cause, B]): Task[Decision[B]] = Task.delay {
    implicit val requestId: RequestId = blockContext.requestContext.id.toRequestId
    val result = for {
      token <- extractJwtTokenFromHeader(blockContext, jwt)
      jwtPayload <- claimsFrom(token, jwt)
      claimProcessingResult <- operation(jwtPayload)
    } yield claimProcessingResult
    result match {
      case Right(modifiedBlockContext) => Permitted(modifiedBlockContext)
      case Left(cause) => Denied(cause)
    }
  }

  private def checkAuthenticationTokenValidity(service: ExternalAuthenticationService, token: Jwt.Token)
                                              (implicit requestId: RequestId) = EitherT {
    service.authenticate(Credentials(User.Id(nes("jwt")), PlainTextSecret(token.value)))
  }

  private def extractJwtTokenFromHeader[JWT_DEF <: JwtDef](blockContext: BlockContext, jwt: JWT_DEF) = {
    blockContext
      .requestContext
      .authorizationToken(jwt.authorizationTokenDef)
      .map(h => Jwt.Token(h.value))
    blockContext
      .requestContext
      .bearerToken
      .map(h => Jwt.Token(h.value))
      .toRight(AuthenticationFailed("No bearer token found"))
  }

  private def claimsFrom[JWT_DEF <: JwtDef](token: Jwt.Token, jwt: JWT_DEF)
                                           (implicit requestId: RequestId) = {
    val parser = jwt.checkMethod match {
      case NoCheck(_) => Jwts.parser().unsecured().build()
      case Hmac(rawKey) => Jwts.parser().verifyWith(Keys.hmacShaKeyFor(rawKey)).build()
      case Rsa(pubKey) => Jwts.parser().verifyWith(pubKey).build()
      case Ec(pubKey) => Jwts.parser().verifyWith(pubKey).build()
    }

    def causeFrom(ex: Throwable): Cause = {
      logBadToken(ex, token)
      AuthenticationFailed(s"JWT token validation failed: ${ex.getMessage}")
    }

    jwt.checkMethod match {
      case NoCheck(_) =>
        token.value.value.split("\\.").toList match {
          case fst :: snd :: _ =>
            Try(parser.parseUnsecuredClaims(s"$fst.$snd.").getPayload)
              .toEither
              .fold(ex => Left(causeFrom(ex)), claims => Right(Jwt.Payload(claims)))
          case _ =>
            Left(AuthenticationFailed("Malformed JWT token structure"))
        }
      case Hmac(_) | Rsa(_) | Ec(_) =>
        Try(parser.parseSignedClaims(token.value.value).getPayload)
          .toEither
          .map(Jwt.Payload.apply)
          .fold(ex => Left(causeFrom(ex)), Right(_))
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

}
