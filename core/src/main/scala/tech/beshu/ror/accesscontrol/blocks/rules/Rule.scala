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

import cats.{Monad, Show}
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.GeneralNonIndexRequestBlockContextUpdater
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Fulfilled
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{AuthenticationImpersonationSupport, AuthorizationImpersonationSupport}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, User}
import tech.beshu.ror.accesscontrol.utils.TaskRuleResultOps.*
import tech.beshu.ror.syntax.*

import scala.annotation.{nowarn, tailrec}

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

  sealed trait RuleResult[B]

  object RuleResult {
    final case class Fulfilled[B](blockContext: B)
      extends RuleResult[B]

    final case class Rejected[B](specialCause: Option[Cause] = None)
      extends RuleResult[B]
    object Rejected {
      def apply[B <: BlockContext](specialCause: Cause): Rejected[B] = new Rejected(Some(specialCause))
      sealed trait Cause
      object Cause {
        case object ImpersonationNotSupported extends Cause
        case object ImpersonationNotAllowed extends Cause
        final case class IndexNotFound(allowedClusters: Set[ClusterName.Full]) extends Cause
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

    def fromOption[A](opt: Option[A], ifEmpty: => Rejected[A] = Rejected[A]()): RuleResult[A] =
      opt match {
        case Some(value) => Fulfilled(value)
        case None => ifEmpty
      }

    extension [B](result: RuleResult[B]) {
      def withFilter(p: B => Boolean): RuleResult[B] =
        result match {
          case RuleResult.Fulfilled(a) if p(a) => result
          case _ => RuleResult.Rejected(None)
        }
    }

    implicit val ruleResultMonad: Monad[RuleResult] = new Monad[RuleResult] {
      override def pure[A](a: A): RuleResult[A] =
        RuleResult.Fulfilled(a)

      override def flatMap[A, B](fa: RuleResult[A])(f: A => RuleResult[B]): RuleResult[B] =
        fa match {
          case RuleResult.Fulfilled(value) => f(value)
          case RuleResult.Rejected(cause) => RuleResult.Rejected(cause)
        }

      @tailrec
      override def tailRecM[A, B](a: A)(f: A => RuleResult[Either[A, B]]): RuleResult[B] =
        f(a) match {
          case RuleResult.Fulfilled(Left(next)) => tailRecM(next)(f)
          case RuleResult.Fulfilled(Right(b)) => RuleResult.Fulfilled(b)
          case RuleResult.Rejected(cause) => RuleResult.Rejected(cause)
        }
    }
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
      blockContext.requestContext.restRequest.path.isAuditEventPath
    }

  }

  trait AuthenticationRule extends Rule {
    this: AuthenticationImpersonationSupport =>

    def eligibleUsers: AuthenticationRule.EligibleUsersSupport
    implicit def userIdCaseSensitivity: CaseSensitivity

    override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] = {
      authenticate(blockContext)
        .flatMapT(postAuthenticateAction)
    }

    private[rules] final def doAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] = {
      authenticate(blockContext)
    }

    protected def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]]

    @nowarn("msg=unused implicit parameter")
    protected def postAuthenticateAction[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
      Task.now(Fulfilled(blockContext))
  }
  object AuthenticationRule {
    sealed trait EligibleUsersSupport
    object EligibleUsersSupport {
      final case class Available(users: Set[User.Id]) extends EligibleUsersSupport
      case object NotAvailable extends EligibleUsersSupport
    }
  }

  trait AuthorizationRule extends Rule {
    this: AuthorizationImpersonationSupport =>

    override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
      authorize(blockContext)
        .flatMapT(postAuthorizationAction)
    }

    private[rules] def doAuthorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] = {
      authorize(blockContext)
    }

    protected def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]]

    @nowarn("msg=unused implicit parameter")
    protected def postAuthorizationAction[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
      Task.now(Fulfilled(blockContext))
  }

  trait AuthRule extends AuthenticationRule with AuthorizationRule {
    this: AuthenticationImpersonationSupport with AuthorizationImpersonationSupport =>

    override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
      authenticate(blockContext)
        .flatMapT(authorize)
        .flatMapT(postAuthAction)
    }

    @nowarn("msg=unused implicit parameter")
    protected def postAuthAction[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
      Task.now(Fulfilled(blockContext))
  }

}
