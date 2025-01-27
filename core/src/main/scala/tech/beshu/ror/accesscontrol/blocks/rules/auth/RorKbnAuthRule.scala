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
package tech.beshu.ror.accesscontrol.blocks.rules.auth

import io.jsonwebtoken.Jwts
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef.SignatureCheckMethod.{Ec, Hmac, Rsa}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.RorKbnAuthRule.{Groups, Settings}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseAuthorizationRule.GroupsPotentiallyPermittedByRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{AuthenticationImpersonationCustomSupport, AuthorizationImpersonationCustomSupport}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.request.RequestContextOps.*
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.*
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult.{Found, NotFound}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.json.JsonPath
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.util.Try

final class RorKbnAuthRule(val settings: Settings,
                           override val userIdCaseSensitivity: CaseSensitivity)
  extends AuthRule
    with AuthenticationImpersonationCustomSupport
    with AuthorizationImpersonationCustomSupport
    with Logging {

  override val name: Rule.Name = RorKbnAuthRule.Name.name

  override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable

  private val parser = settings.rorKbn.checkMethod match {
    case Hmac(rawKey) => Jwts.parserBuilder().setSigningKey(rawKey).build()
    case Rsa(pubKey) => Jwts.parserBuilder().setSigningKey(pubKey).build()
    case Ec(pubKey) => Jwts.parserBuilder().setSigningKey(pubKey).build()
  }

  override protected[rules] def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
    Task.now(RuleResult.Fulfilled(blockContext))

  override protected[rules] def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
    Task {
      settings.permittedGroups match {
        case Groups.NotDefined =>
          authorizeUsingJwtToken(blockContext)
        case Groups.Defined(groupsLogic) if blockContext.isCurrentGroupPotentiallyEligible(GroupsPotentiallyPermittedByRule.from(groupsLogic)) =>
          authorizeUsingJwtToken(blockContext)
        case Groups.Defined(_) =>
          RuleResult.Rejected()
      }
    }

  private def authorizeUsingJwtToken[B <: BlockContext : BlockContextUpdater](blockContext: B): RuleResult[B] = {
    val authHeaderName = Header.Name.authorization
    blockContext.requestContext.bearerToken.map(h => Jwt.Token(h.value)) match {
      case None =>
        logger.debug(s"[${blockContext.requestContext.id.show}] Authorization header '${authHeaderName.show}' is missing or does not contain a bearer token")
        Rejected()
      case Some(token) =>
        process(token, blockContext)
    }
  }

  private def process[B <: BlockContext : BlockContextUpdater](token: Jwt.Token, blockContext: B): RuleResult[B] = {
    implicit val requestId: RequestId = blockContext.requestContext.id.toRequestId
    jwtTokenData(token) match {
      case Left(_) =>
        Rejected()
      case Right((tokenPayload, user, groups, userOrigin)) =>
        val claimProcessingResult = for {
          newBlockContext <- handleUserClaimSearchResult(blockContext, user)
          finalBlockContext <- handleGroupsClaimSearchResult(newBlockContext, groups)
        } yield handleUserOriginResult(finalBlockContext, userOrigin).withUserMetadata(_.withJwtToken(tokenPayload))
        claimProcessingResult match {
          case Left(_) =>
            Rejected()
          case Right(modifiedBlockContext) =>
            Fulfilled(modifiedBlockContext)
        }
    }
  }

  private def jwtTokenData(token: Jwt.Token)
                          (implicit requestId: RequestId) = {
    claimsFrom(token)
      .map { tokenPayload =>
        (
          tokenPayload,
          tokenPayload.claims.userIdClaim(RorKbnAuthRule.userClaimName),
          tokenPayload.claims.groupsClaim(groupIdsClaimName = RorKbnAuthRule.groupIdsClaimName, groupNamesClaimName = None),
          tokenPayload.claims.headerNameClaim(Header.Name.xUserOrigin)
        )
      }
  }

  private def claimsFrom(token: Jwt.Token)
                        (implicit requestId: RequestId) = {
    Try(parser.parseClaimsJws(token.value.value).getBody)
      .toEither
      .map(Jwt.Payload.apply)
      .left.map { ex => logger.debug(s"[${requestId.show}] JWT token '${token.show}' parsing error " + ex.getClass.getSimpleName) }
  }

  private def handleUserClaimSearchResult[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                                   result: ClaimSearchResult[User.Id]) = {
    result match {
      case Found(userId) => Right(blockContext.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(userId))))
      case NotFound => Left(())
    }
  }

  private def handleGroupsClaimSearchResult[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                                     result: ClaimSearchResult[UniqueList[Group]]) = {
    (result, settings.permittedGroups) match {
      case (NotFound, Groups.Defined(_)) =>
        Left(())
      case (NotFound, Groups.NotDefined) =>
        Right(blockContext) // if groups field is not found, we treat this situation as same as empty groups would be passed
      case (Found(groups), Groups.Defined(groupsLogic)) =>
        UniqueNonEmptyList.from(groups) match {
          case Some(nonEmptyGroups) =>
            groupsLogic.availableGroupsFrom(nonEmptyGroups) match {
              case Some(matchedGroups) if blockContext.isCurrentGroupEligible(GroupIds.from(matchedGroups)) =>
                Right(blockContext.withUserMetadata(_.addAvailableGroups(matchedGroups)))
              case Some(_) | None =>
                Left(())
            }
          case None =>
            Left(())
        }
      case (Found(groups), Groups.NotDefined) =>
        UniqueNonEmptyList.from(groups) match {
          case None =>
            Right(blockContext)
          case Some(nonEmptyGroups) if blockContext.isCurrentGroupEligible(GroupIds.from(nonEmptyGroups)) =>
            Right(blockContext)
          case Some(_) =>
            Left(())
        }
    }
  }

  private def handleUserOriginResult[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                              result: ClaimSearchResult[Header]): B = {
    result match {
      case Found(header) => blockContext.withUserMetadata(_.withUserOrigin(UserOrigin(header.value)))
      case ClaimSearchResult.NotFound => blockContext
    }
  }
}

object RorKbnAuthRule {

  implicit case object Name extends RuleName[RorKbnAuthRule] {
    override val name = Rule.Name("ror_kbn_auth")
  }

  final case class Settings(rorKbn: RorKbnDef, permittedGroups: Groups)

  sealed trait Groups

  object Groups {
    case object NotDefined extends Groups

    final case class Defined(groupsLogic: GroupsLogic) extends Groups
  }

  private val userClaimName = Jwt.ClaimName(JsonPath("user").get)
  private val groupIdsClaimName = Jwt.ClaimName(JsonPath("groups").get)
}
