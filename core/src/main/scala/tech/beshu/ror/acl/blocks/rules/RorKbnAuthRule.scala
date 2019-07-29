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
package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import cats.implicits._
import io.jsonwebtoken.Jwts
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.domain._
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.definitions.RorKbnDef
import tech.beshu.ror.acl.blocks.definitions.RorKbnDef.SignatureCheckMethod.{Ec, Hmac, Rsa}
import tech.beshu.ror.acl.blocks.rules.RorKbnAuthRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule, NoImpersonationSupport, RuleResult}
import tech.beshu.ror.acl.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.acl.utils.ClaimsOps.ClaimSearchResult.{Found, NotFound}
import tech.beshu.ror.acl.utils.ClaimsOps._
import tech.beshu.ror.com.jayway.jsonpath.JsonPath
import tech.beshu.ror.utils.LoggerOps._

import scala.collection.SortedSet
import scala.util.Try

class RorKbnAuthRule(val settings: Settings)
  extends AuthenticationRule
    with NoImpersonationSupport
    with AuthorizationRule
    with Logging {

  override val name: Rule.Name = RorKbnAuthRule.name

  private val parser = settings.rorKbn.checkMethod match {
    case Hmac(rawKey) => Jwts.parser.setSigningKey(rawKey)
    case Rsa(pubKey) => Jwts.parser.setSigningKey(pubKey)
    case Ec(pubKey) => Jwts.parser.setSigningKey(pubKey)
  }

  override def tryToAuthenticate(requestContext: RequestContext,
                                 blockContext: BlockContext): Task[RuleResult] = Task {
    val authHeaderName = Header.Name.authorization
    requestContext.bearerToken.map(h => JwtToken(h.value)) match {
      case None =>
        logger.debug(s"Authorization header '${authHeaderName.show}' is missing or does not contain a bearer token")
        Rejected()
      case Some(token) =>
        process(token, blockContext)
    }
  }

  private def process(token: JwtToken, blockContext: BlockContext) = {
    jwtTokenData(token) match {
      case Left(_) =>
        Rejected()
      case Right((tokenPayload, user, groups, userOrigin)) =>
        val claimProcessingResult = for {
          newBlockContext <- handleUserClaimSearchResult(blockContext, user)
          finalBlockContext <- handleGroupsClaimSearchResult(newBlockContext, groups)
        } yield handleUserOriginResult(finalBlockContext, userOrigin).withJsonToken(tokenPayload)
        claimProcessingResult match {
          case Left(_) =>
            Rejected()
          case Right(modifiedBlockContext) =>
            Fulfilled(modifiedBlockContext)
        }
    }
  }

  private def jwtTokenData(token: JwtToken) = {
    claimsFrom(token)
      .map { tokenPayload =>
        (
          tokenPayload,
          tokenPayload.claims.userIdClaim(RorKbnAuthRule.userClaimName),
          tokenPayload.claims.groupsClaim(RorKbnAuthRule.groupsClaimName),
          tokenPayload.claims.headerNameClaim(Header.Name.xUserOrigin)
        )
      }
  }

  private def claimsFrom(token: JwtToken) = {
    Try(parser.parseClaimsJws(token.value.value).getBody)
      .toEither
      .map(JwtTokenPayload.apply)
      .left.map { ex =>
      logger.errorEx(s"JWT token '${token.show}' parsing error", ex)
      ()
    }
  }

  private def handleUserClaimSearchResult(blockContext: BlockContext, result: ClaimSearchResult[User.Id]) = {
    result match {
      case Found(userId) => Right(blockContext.withLoggedUser(DirectlyLoggedUser(userId)))
      case NotFound => Left(())
    }
  }

  private def handleGroupsClaimSearchResult(blockContext: BlockContext, result: ClaimSearchResult[Set[Group]]) = {
    result match {
      case NotFound if settings.groups.nonEmpty => Left(())
      case NotFound => Right(blockContext) // if groups field is not found, we treat this situation as same as empty groups would be passed
      case Found(groups) if settings.groups.nonEmpty =>
        NonEmptySet.fromSet(SortedSet.empty[Group] ++ groups.intersect(settings.groups)) match {
          case Some(matchedGroups) => Right {
            blockContext
              .withCurrentGroup(matchedGroups.head)
              .withAddedAvailableGroups(matchedGroups)
          }
          case None => Left(())
        }
      case Found(_) => Right(blockContext)
    }
  }

  private def handleUserOriginResult(blockContext: BlockContext, result: ClaimSearchResult[Header]): BlockContext = {
    result match {
      case Found(header) => blockContext.withAddedResponseHeader(header)
      case ClaimSearchResult.NotFound => blockContext
    }
  }

}

object RorKbnAuthRule {
  val name = Rule.Name("ror_kbn_auth")

  final case class Settings(rorKbn: RorKbnDef, groups: Set[Group])

  private val userClaimName = ClaimName(JsonPath.compile("user"))
  private val groupsClaimName = ClaimName(JsonPath.compile("groups"))
}
