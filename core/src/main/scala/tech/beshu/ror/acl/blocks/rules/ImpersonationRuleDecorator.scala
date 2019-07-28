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

import cats.data.EitherT
import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.acl.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.acl.blocks.rules.Rule.ImpersonationSupport.UserExistence._
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, ImpersonationSupport}
import tech.beshu.ror.acl.blocks.rules.utils.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.acl.blocks.rules.utils.StringTNaturalTransformation.instances.stringUserIdNT
import tech.beshu.ror.acl.blocks.{BlockContext, NoOpBlockContext}
import tech.beshu.ror.acl.domain.LoggedUser.ImpersonatedUser
import tech.beshu.ror.acl.domain.User
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._
import tech.beshu.ror.utils.MatcherWithWildcards

import scala.collection.JavaConverters._

class ImpersonationRuleDecorator[R <: AuthenticationRule with ImpersonationSupport](val underlying: R,
                                                                                    impersonators: List[ImpersonatorDef])
  extends AuthenticationRule {

  private val enhancedImpersonatorDefs =
    impersonators
      .map { i =>
        val matcher = new MatcherWithWildcardsScalaAdapter(
          new MatcherWithWildcards(i.users.map(_.value.value).toSortedSet.asJava)
        )
        (i, matcher)
      }
  override val name: Rule.Name = underlying.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[Rule.RuleResult] = {
    requestContext.impersonateAs match {
      case Some(theImpersonatedUserId) => toRuleResult {
        for {
          impersonatorDef <- findImpersonatorWithProperRights(theImpersonatedUserId, requestContext)
          _ <- authenticateImpersonator(impersonatorDef, requestContext)
          _ <- checkIfTheImpersonatedUserExist(theImpersonatedUserId)
        } yield blockContext.withLoggedUser(ImpersonatedUser(theImpersonatedUserId, impersonatorDef.id))
      }
      case None =>
        underlying.check(requestContext, blockContext)
    }
  }

  private def findImpersonatorWithProperRights(theImpersonatedUserId: User.Id,
                                               requestContext: RequestContext) = {
    EitherT.fromOption[Task](
      requestContext
        .basicAuth
        .flatMap { basicAuthCredentials =>
          enhancedImpersonatorDefs.find(_._1.id === basicAuthCredentials.credentials.user)
        }
        .flatMap { case (impersonatorDef, matcher) =>
          if (matcher.`match`(theImpersonatedUserId)) Some(impersonatorDef)
          else None
        },
      ifNone = Rejected(Cause.ImpersonationNotAllowed)
    )
  }

  private def authenticateImpersonator(impersonatorDef: ImpersonatorDef,
                                       requestContext: RequestContext) = EitherT {
    impersonatorDef
      .authenticationRule
      .check(requestContext, NoOpBlockContext)
      .map {
        case Fulfilled(_) => Right(())
        case Rejected(_) => Left(Rejected(Cause.ImpersonationNotAllowed))
      }
  }

  private def checkIfTheImpersonatedUserExist(theImpersonatedUserId: User.Id) = EitherT {
    underlying
      .exists(theImpersonatedUserId)
      .map {
        case Exists => Right(())
        case NotExist => Left(Rejected())
        case CannotCheck => Left(Rejected(Cause.ImpersonationNotSupported))
      }
  }

  private def toRuleResult(result: EitherT[Task, Rejected, BlockContext]) = {
    result
      .value
      .map {
        case Right(newBlockContext) => Fulfilled(newBlockContext)
        case Left(rejected) => rejected
      }
  }
}
