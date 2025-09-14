package tech.beshu.ror.accesscontrol.blocks.rules.auth.base

import cats.implicits.toShow
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef.SignatureCheckMethod.{Ec, Hmac, Rsa}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.request.RequestContextOps.from
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.*
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult.{Found, NotFound}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.json.JsonPath
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.util.Try

object BaseRorKbnRule extends Logging {

  def processUsingJwtToken[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                    operation: RorKbnOperation): RuleResult[B] = {
    val authHeaderName = Header.Name.authorization
    blockContext.requestContext.bearerToken.map(h => Jwt.Token(h.value)) match {
      case None =>
        logger.debug(s"[${blockContext.requestContext.id.show}] Authorization header '${authHeaderName.show}' is missing or does not contain a bearer token")
        Rejected()
      case Some(token) =>
        process(token, blockContext, operation)
    }
  }

  private def process[B <: BlockContext : BlockContextUpdater](token: Jwt.Token,
                                                               blockContext: B,
                                                               operation: RorKbnOperation): RuleResult[B] = {
    implicit val requestId: RequestId = blockContext.requestContext.id.toRequestId
    jwtTokenData(token, operation.rorKbn) match {
      case Left(_) =>
        Rejected()
      case Right((tokenPayload, user, groups, userOrigin)) =>
        val claimProcessingResult = operation match {
          case RorKbnOperation.Authenticate(_) =>
            for {
              newBlockContext <- handleUserClaimSearchResult(blockContext, user)
              finalBlockContext <- handleGroupsClaimSearchResult(newBlockContext, groups)
            } yield handleUserOriginResult(finalBlockContext, userOrigin).withUserMetadata(_.withJwtToken(tokenPayload))
          case RorKbnOperation.Authorize(_, permittedGroups) =>
            handleGroupsClaimSearchResult(blockContext, groups, permittedGroups)
          case RorKbnOperation.AuthenticateAndAuthorize(_, permittedGroups) =>
            for {
              newBlockContext <- handleUserClaimSearchResult(blockContext, user)
              finalBlockContext <- handleGroupsClaimSearchResult(newBlockContext, groups, permittedGroups)
            } yield handleUserOriginResult(finalBlockContext, userOrigin).withUserMetadata(_.withJwtToken(tokenPayload))
        }
        claimProcessingResult match {
          case Left(_) =>
            Rejected()
          case Right(modifiedBlockContext) =>
            Fulfilled(modifiedBlockContext)
        }
    }
  }

  private def jwtTokenData(token: Jwt.Token, rorKbn: RorKbnDef)
                          (implicit requestId: RequestId) = {
    claimsFrom(token, rorKbn)
      .map { tokenPayload =>
        (
          tokenPayload,
          tokenPayload.claims.userIdClaim(userClaimName),
          tokenPayload.claims.groupsClaim(groupIdsClaimName = groupIdsClaimName, groupNamesClaimName = None),
          tokenPayload.claims.headerNameClaim(Header.Name.xUserOrigin)
        )
      }
  }

  private def claimsFrom(token: Jwt.Token, rorKbn: RorKbnDef)
                        (implicit requestId: RequestId) = {
    Try(parser(rorKbn).parseSignedClaims(token.value.value).getPayload)
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
                                                                                     result: ClaimSearchResult[UniqueList[Group]],
                                                                                     groupsLogic: GroupsLogic) = {
    (result, groupsLogic) match {
      case (NotFound, _) =>
        Left(())
      case (Found(groups), groupsLogic) =>
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
    }
  }

  private def handleGroupsClaimSearchResult[B <: BlockContext](blockContext: B,
                                                               result: ClaimSearchResult[UniqueList[Group]]) = {
    result match {
      case NotFound =>
        Right(blockContext) // if groups field is not found, we treat this situation as same as empty groups would be passed
      case Found(groups) =>
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

  private def parser(rorKbn: RorKbnDef) = rorKbn.checkMethod match {
    case Hmac(rawKey) => Jwts.parser().verifyWith(Keys.hmacShaKeyFor(rawKey)).build()
    case Rsa(pubKey) => Jwts.parser().verifyWith(pubKey).build()
    case Ec(pubKey) => Jwts.parser().verifyWith(pubKey).build()
  }

  private val userClaimName = Jwt.ClaimName(JsonPath("user").get)
  private val groupIdsClaimName = Jwt.ClaimName(JsonPath("groups").get)

  sealed trait RorKbnOperation {
    def rorKbn: RorKbnDef
  }

  object RorKbnOperation {
    final case class Authenticate(rorKbn: RorKbnDef) extends RorKbnOperation

    final case class Authorize(rorKbn: RorKbnDef, groupsLogic: GroupsLogic) extends RorKbnOperation

    final case class AuthenticateAndAuthorize(rorKbn: RorKbnDef, groupsLogic: GroupsLogic) extends RorKbnOperation
  }

}
