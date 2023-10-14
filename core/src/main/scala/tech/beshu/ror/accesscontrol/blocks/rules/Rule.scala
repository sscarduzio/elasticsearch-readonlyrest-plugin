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

import cats.Show
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.GeneralNonIndexRequestBlockContextUpdater
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{AuthenticationImpersonationSupport, AuthorizationImpersonationSupport}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.GlobPattern.CaseSensitivity
import tech.beshu.ror.accesscontrol.domain.User

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

  trait AuthenticationRule extends Rule {
    this: AuthenticationImpersonationSupport =>

    def eligibleUsers: AuthenticationRule.EligibleUsersSupport
    implicit def userIdCaseSensitivity: CaseSensitivity

    override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] = {
      authenticate(blockContext)
    }

    protected def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]]
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
    }

    protected def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]]
  }

  trait AuthRule extends AuthenticationRule with AuthorizationRule {
    this: AuthenticationImpersonationSupport with AuthorizationImpersonationSupport =>

    override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
      authenticate(blockContext)
        .flatMap {
          case Fulfilled(newBlockContext) =>
            authorize(newBlockContext)
          case rejected@Rejected(_) =>
            Task.now(rejected)
        }
    }
  }

}
