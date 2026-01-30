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
import tech.beshu.ror.accesscontrol.blocks.Decision.Permitted
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{AuthenticationImpersonationSupport, AuthorizationImpersonationSupport}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, User}
import tech.beshu.ror.accesscontrol.utils.TaskResultOps.*
import tech.beshu.ror.syntax.*

import scala.annotation.nowarn

sealed trait Rule {
  def name: Rule.Name

  def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]]
}

object Rule {

  trait RuleName[T <: Rule] {
    def name: Rule.Name
  }
  object RuleName {
    def apply[T <: Rule](implicit ev: RuleName[T]): RuleName[T] = ev
  }

  final case class Name(value: String) extends AnyVal
  object Name {
    implicit val show: Show[Name] = Show.show(_.value)
  }

  trait MatchingAlwaysRule extends RegularRule {

    def process[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[B]

    override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] =
      process(blockContext).map(Decision.Permitted.apply)
  }

  trait RegularRule extends Rule {
    override final def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
      BlockContextUpdater[B] match {
        case GeneralNonIndexRequestBlockContextUpdater if isAuditEventRequest(blockContext) =>
          Task.now(Decision.permit(blockContext))
        case _ =>
          regularCheck(blockContext)
      }
    }

    protected def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]]

    private def isAuditEventRequest(blockContext: GeneralNonIndexRequestBlockContext): Boolean = {
      blockContext.requestContext.restRequest.path.isAuditEventPath
    }

  }

  trait AuthenticationRule extends Rule {
    this: AuthenticationImpersonationSupport =>

    def eligibleUsers: AuthenticationRule.EligibleUsersSupport
    implicit def userIdCaseSensitivity: CaseSensitivity

    override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
      authenticate(blockContext)
        .flatMapT(postAuthenticateAction)
    }

    private[rules] final def doAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
      authenticate(blockContext)
    }

    protected def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]]

    @nowarn("msg=unused implicit parameter")
    protected def postAuthenticateAction[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] =
      Task.now(Permitted(blockContext))
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

    override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
      authorize(blockContext)
        .flatMapT(postAuthorizationAction)
    }

    private[rules] def doAuthorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
      authorize(blockContext)
    }

    protected def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]]

    @nowarn("msg=unused implicit parameter")
    protected def postAuthorizationAction[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] =
      Task.now(Permitted(blockContext))
  }

  trait AuthRule extends AuthenticationRule with AuthorizationRule {
    this: AuthenticationImpersonationSupport with AuthorizationImpersonationSupport =>

    override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
      authenticate(blockContext)
        .flatMapT(authorize)
        .flatMapT(postAuthAction)
    }

    @nowarn("msg=unused implicit parameter")
    protected def postAuthAction[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] =
      Task.now(Permitted(blockContext))
  }

}
