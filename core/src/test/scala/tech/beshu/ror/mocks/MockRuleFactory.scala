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
package tech.beshu.ror.mocks

import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.{Denied, Permitted}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthRule, RegularRule}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{
  AuthenticationImpersonationCustomSupport,
  AuthorizationImpersonationCustomSupport
}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, Group, KibanaAccess, KibanaIndexName, LocalUsers, User}
import tech.beshu.ror.utils.TestsUtils.{*, given}
import tech.beshu.ror.utils.uniquelist.UniqueList

import java.util.concurrent.atomic.AtomicInteger
import scala.util.Failure

trait MockRuleFactory {

  protected def passingRule(ruleName: String): RegularRule = new RegularRule {
    override val name: Rule.Name = Rule.Name(ruleName)

    override protected def regularCheck[B <: BlockContext: BlockContextUpdater](blockContext: B): Task[Decision[B]] =
      Task.now(Permitted(blockContext))
  }

  protected def notPassingRule(ruleName: String): RegularRule = new RegularRule {
    override val name: Rule.Name = Rule.Name(ruleName)

    override protected def regularCheck[B <: BlockContext: BlockContextUpdater](blockContext: B): Task[Decision[B]] =
      Task.now(Denied(Cause.NotAuthorized))
  }

  protected def throwingRule(ruleName: String): RegularRule = new RegularRule {
    override val name: Rule.Name = Rule.Name(ruleName)

    override protected def regularCheck[B <: BlockContext: BlockContextUpdater](blockContext: B): Task[Decision[B]] =
      Task.fromTry(Failure(new Exception("sth went wrong")))
  }

  protected def passingAuthRule(
      ruleName: String,
      userId: User.Id,
      groups: UniqueList[Group],
      authInvocations: AtomicInteger = new AtomicInteger(0)
  ): AuthRule =
    new AuthRule with AuthenticationImpersonationCustomSupport with AuthorizationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name(ruleName)

      override def localUsers: LocalUsers = LocalUsers.NotAvailable
      override implicit def userIdCaseSensitivity: CaseSensitivity = CaseSensitivity.Disabled

      override protected def authenticate[B <: BlockContext: BlockContextUpdater](
          blockContext: B
      ): Task[Decision[B]] = {
        authInvocations.incrementAndGet()
        Task.now(Permitted(blockContext.withBlockMetadata(_.withLoggedUser(DirectlyLoggedUser(userId)))))
      }

      override protected def authorize[B <: BlockContext: BlockContextUpdater](blockContext: B): Task[Decision[B]] =
        Task.now(Permitted(blockContext.withBlockMetadata(_.withAvailableGroups(groups))))
    }

  protected def notPassingAuthRule(ruleName: String): AuthRule =
    new AuthRule with AuthenticationImpersonationCustomSupport with AuthorizationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name(ruleName)

      override def localUsers: LocalUsers = LocalUsers.NotAvailable
      override implicit def userIdCaseSensitivity: CaseSensitivity = CaseSensitivity.Disabled

      override protected def authenticate[B <: BlockContext: BlockContextUpdater](blockContext: B): Task[Decision[B]] =
        Task.now(Denied(Cause.AuthenticationFailed("mock failed")))

      override protected def authorize[B <: BlockContext: BlockContextUpdater](blockContext: B): Task[Decision[B]] =
        Task.now(Denied(Cause.AuthenticationFailed("mock failed")))
    }

  protected def authorizationRule(ruleName: String, userId: User.Id, groups: UniqueList[Group]): Rule =
    new Rule.AuthorizationRule with AuthorizationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name(ruleName)

      override protected def authorize[B <: BlockContext: BlockContextUpdater](blockContext: B): Task[Decision[B]] =
        Task.now(
          Permitted(
            blockContext.withBlockMetadata(
              _.withLoggedUser(DirectlyLoggedUser(userId))
                .withAvailableGroups(groups)
            )
          )
        )
    }

  protected def kibanaAccessRule(ruleName: String, access: KibanaAccess): RegularRule = new RegularRule {
    override val name: Rule.Name = Rule.Name(ruleName)

    override protected def regularCheck[B <: BlockContext: BlockContextUpdater](blockContext: B): Task[Decision[B]] =
      Task.now(Permitted(blockContext.withBlockMetadata(_.withKibanaAccess(access))))
  }

  protected def kibanaIndexRule(ruleName: String, index: KibanaIndexName): RegularRule = new RegularRule {
    override val name: Rule.Name = Rule.Name(ruleName)

    override protected def regularCheck[B <: BlockContext: BlockContextUpdater](blockContext: B): Task[Decision[B]] =
      Task.now(Permitted(blockContext.withBlockMetadata(_.withKibanaIndex(index))))
  }

  protected def perGroupKibanaIndexRule(ruleName: String): RegularRule = new RegularRule {
    override val name: Rule.Name = Rule.Name(ruleName)

    override protected def regularCheck[B <: BlockContext: BlockContextUpdater](blockContext: B): Task[Decision[B]] =
      blockContext.blockMetadata.currentGroupId match {
        case Some(groupId) =>
          val index = kibanaIndexName(NonEmptyString.unsafeFrom(s"kibana_${groupId.value.value}"))
          Task.now(Permitted(blockContext.withBlockMetadata(_.withKibanaIndex(index))))
        case None =>
          Task.now(Permitted(blockContext))
      }
  }

}
