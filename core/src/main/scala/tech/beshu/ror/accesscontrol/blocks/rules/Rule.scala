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

import cats.data.EitherT
import cats.implicits._
import cats.{Eq, Show}
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.GeneralNonIndexRequestBlockContextUpdater
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.UserExistence
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.UserExistence.{CannotCheck, Exists, NotExist}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.VariableUsage
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.ImpersonatedUser
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.matchers.{GenericPatternMatcher, MatcherWithWildcardsScalaAdapter}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.utils.CaseMappingEquality._

sealed trait Rule {
  def name: Rule.Name

  def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]]
}

object Rule {

  trait RuleName[T <: Rule] {
    def name: Rule.Name
  }
  object RuleName {
    def apply[T <: Rule](implicit ev: RuleName[T]): RuleName[T] = ev
  }

  final case class RuleWithVariableUsageDefinition[+T <: Rule](rule: T, variableUsage: VariableUsage[T])

  object RuleWithVariableUsageDefinition {
    def create[T <: Rule : VariableUsage](rule: T) = new RuleWithVariableUsageDefinition(rule, implicitly[VariableUsage[T]])
  }

  sealed trait RuleResult[B <: BlockContext]
  object RuleResult {
    final case class Fulfilled[B <: BlockContext](blockContext: B)
      extends RuleResult[B]
    final case class Rejected[B <: BlockContext](specialCause: Option[Cause] = None)
      extends RuleResult[B]
    object Rejected {
      def apply[B <: BlockContext](specialCause: Cause): Rejected[B] = new Rejected(Some(specialCause))
      sealed trait Cause
      object Cause {
        case object ImpersonationNotSupported extends Cause
        case object ImpersonationNotAllowed extends Cause
        case object IndexNotFound extends Cause
        case object AliasNotFound extends Cause
        case object TemplateNotFound extends Cause
      }
    }

    private[rules] def resultBasedOnCondition[B <: BlockContext](blockContext: B)(condition: => Boolean): RuleResult[B] = {
      if (condition) Fulfilled[B](blockContext)
      else Rejected[B]()
    }

    private[rules] def fulfilled[B <: BlockContext](blockContext: B): RuleResult[B] = RuleResult.Fulfilled(blockContext)
    private[rules] def rejected[B <: BlockContext](specialCause: Option[Cause] = None): RuleResult[B] = RuleResult.Rejected(specialCause)
  }

  final case class Name(value: String) extends AnyVal
  object Name {
    implicit val show: Show[Name] = Show.show(_.value)
  }

  trait MatchingAlwaysRule extends RegularRule {

    def process[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[B]

    override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
      process(blockContext).map(RuleResult.Fulfilled.apply)
  }

  trait RegularRule extends Rule {
    override final def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
      BlockContextUpdater[B] match {
        case GeneralNonIndexRequestBlockContextUpdater if isAuditEventRequest(blockContext) =>
          Task.now(RuleResult.fulfilled(blockContext))
        case _ =>
          regularCheck(blockContext)
      }
    }

    protected def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]]

    private def isAuditEventRequest(blockContext: GeneralNonIndexRequestBlockContext): Boolean = {
      blockContext.requestContext.uriPath.isAuditEventPath
    }

  }

  trait AuthRule extends AuthenticationRule with AuthorizationRule

  trait AuthorizationRule extends Rule

  trait AuthenticationRule extends Rule {

    private lazy val enhancedImpersonatorDefs =
      impersonators
        .map { i =>
          val impersonatorMatcher = new GenericPatternMatcher(i.usernames.patterns.toList)(caseMappingEquality)
          val userMatcher = MatcherWithWildcardsScalaAdapter.fromSetString[User.Id](i.users.map(_.value.value).toSet)(caseMappingEquality)
          (i, impersonatorMatcher, userMatcher)
        }

    protected def impersonators: List[ImpersonatorDef]

    protected def exists(user: User.Id)
                        (implicit userIdEq: Eq[User.Id]): Task[UserExistence]

    def eligibleUsers: AuthenticationRule.EligibleUsersSupport

    def tryToAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]]

    override final def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] = {
      implicit val eqUserId: Eq[User.Id] = caseMappingEquality.toOrder
      val requestContext = blockContext.requestContext
      requestContext.impersonateAs match {
        case Some(theImpersonatedUserId) => toRuleResult[B] {
          for {
            impersonatorDef <- findImpersonatorWithProperRights[B](theImpersonatedUserId, requestContext)
            loggedImpersonator <- authenticateImpersonator(impersonatorDef, blockContext)
            _ <- checkIfTheImpersonatedUserExist[B](theImpersonatedUserId)
          } yield {
            blockContext.withUserMetadata(_.withLoggedUser(ImpersonatedUser(theImpersonatedUserId, loggedImpersonator.id)))
          }
        }
        case None =>
          tryToAuthenticate(blockContext)
      }
    }

    def caseMappingEquality: UserIdCaseMappingEquality

    private def findImpersonatorWithProperRights[B <: BlockContext](theImpersonatedUserId: User.Id,
                                                                    requestContext: RequestContext)
                                                                   (implicit userIdEq: Eq[User.Id]) = {
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
        .tryToAuthenticate(BlockContextUpdater[B].emptyBlockContext(blockContext)) // we are not interested in gathering those data
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
                                                                  (implicit userIdEq: Eq[User.Id]) = EitherT {
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
  }
  object AuthenticationRule {
    sealed trait UserExistence
    object UserExistence {
      case object Exists extends UserExistence
      case object NotExist extends UserExistence
      case object CannotCheck extends UserExistence
    }

    sealed trait EligibleUsersSupport
    object EligibleUsersSupport {
      final case class Available(users: Set[User.Id]) extends EligibleUsersSupport
      case object NotAvailable extends EligibleUsersSupport
    }
  }

  trait NoImpersonationSupport {
    this: AuthenticationRule =>

    override protected val impersonators: List[ImpersonatorDef] = Nil

    override final protected def exists(user: User.Id)
                                       (implicit userIdEq: Eq[User.Id]): Task[UserExistence] =
      Task.now(CannotCheck)
  }

}
