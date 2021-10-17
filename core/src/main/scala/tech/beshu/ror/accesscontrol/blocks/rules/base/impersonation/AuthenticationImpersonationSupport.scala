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
package tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation

import cats.Eq
import cats.data.EitherT
import monix.eval.Task
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.{AuthenticationRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.ImpersonationSettingsBasedSupport.UserExistence
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.ImpersonationSettingsBasedSupport.UserExistence.{CannotCheck, Exists, NotExist}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.ImpersonatedUser
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.matchers.{GenericPatternMatcher, MatcherWithWildcardsScalaAdapter}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContextOps._

trait AuthenticationImpersonationSupport

trait ImpersonationSettingsBasedSupport extends AuthenticationImpersonationSupport {
  this: AuthenticationRule =>

  def caseMappingEquality: UserIdCaseMappingEquality
  protected def impersonationSetting: ImpersonationSettings

  private lazy val enhancedImpersonatorDefs =
    impersonationSetting
      .impersonators
      .map { i =>
        val impersonatorMatcher = new GenericPatternMatcher(i.usernames.patterns.toList)(caseMappingEquality)
        val userMatcher = MatcherWithWildcardsScalaAdapter.fromSetString[User.Id](i.users.map(_.value.value).toSet)(caseMappingEquality)
        (i, impersonatorMatcher, userMatcher)
      }

  protected def impersonate[B <: BlockContext : BlockContextUpdater](as: User.Id,
                                                                     blockContext: B): Task[Rule.RuleResult[B]] = {
    implicit val userIdEq: Eq[User.Id] = caseMappingEquality.toOrder
    val theImpersonatedUserId = as
    toRuleResult[B] {
      implicit lazy val requestId: RequestId = blockContext.requestContext.id.toRequestId
      for {
        impersonatorDef <- findImpersonatorWithProperRights[B](theImpersonatedUserId, blockContext.requestContext)
        loggedImpersonator <- authenticateImpersonator(impersonatorDef, blockContext)
        _ <- checkIfTheImpersonatedUserExist[B](theImpersonatedUserId)
      } yield {
        blockContext.withUserMetadata(_.withLoggedUser(ImpersonatedUser(theImpersonatedUserId, loggedImpersonator.id)))
      }
    }
  }

  private def findImpersonatorWithProperRights[B <: BlockContext](theImpersonatedUserId: User.Id,
                                                                  requestContext: RequestContext)
                                                                 (implicit eq: Eq[User.Id]) = {
    EitherT.fromOption[Task](
      requestContext
        .basicAuth
        .flatMap { basicAuthCredentials =>
          enhancedImpersonatorDefs.find { case (_, impersonatorMatcher, _) =>
            impersonatorMatcher.`match`(basicAuthCredentials.credentials.user)
          }
        }
        .flatMap { case (impersonatorDef, _, userMatcher) =>
          if (userMatcher.`match`(theImpersonatedUserId)) Some(impersonatorDef)
          else None
        },
      ifNone = Rejected[B](Cause.ImpersonationNotAllowed)
    )
  }

  private def authenticateImpersonator[B <: BlockContext : BlockContextUpdater](impersonatorDef: ImpersonatorDef,
                                                                                blockContext: B) = EitherT {
    impersonatorDef
      .authenticationRule
      .authenticate(BlockContextUpdater[B].emptyBlockContext(blockContext)) // we are not interested in gathering those data
      .map {
        case Fulfilled(bc) =>
          bc.userMetadata.loggedUser match {
            case Some(loggedUser) => Right(loggedUser)
            case None => throw new IllegalStateException("Impersonator should be logged")
          }
        case Rejected(_) => Left(Rejected[B](Cause.ImpersonationNotAllowed))
      }
  }

  private def checkIfTheImpersonatedUserExist[B <: BlockContext](theImpersonatedUserId: User.Id)
                                                                (implicit requestId: RequestId,
                                                                 eq: Eq[User.Id]) = EitherT {
    exists(theImpersonatedUserId)
      .map {
        case Exists => Right(())
        case NotExist => Left(Rejected[B]())
        case CannotCheck => Left(Rejected[B](Cause.ImpersonationNotSupported))
      }
  }

  private def toRuleResult[B <: BlockContext](result: EitherT[Task, Rejected[B], B]): Task[RuleResult[B]] = {
    result
      .value
      .map {
        case Right(newBlockContext) => Fulfilled[B](newBlockContext)
        case Left(rejected) => rejected
      }
  }

  protected[rules] def exists(user: User.Id)
                             (implicit requestId: RequestId,
                              eq: Eq[User.Id]): Task[UserExistence]

}

object ImpersonationSettingsBasedSupport {
  sealed trait UserExistence
  object UserExistence {
    case object Exists extends UserExistence
    case object NotExist extends UserExistence
    case object CannotCheck extends UserExistence
  }
}

trait AuthenticationImpersonationCustomSupport extends AuthenticationImpersonationSupport
