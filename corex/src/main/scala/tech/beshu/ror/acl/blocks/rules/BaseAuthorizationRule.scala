package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.BaseAuthorizationRule.AuthorizationResult
import tech.beshu.ror.acl.blocks.rules.Rule.AuthorizationRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.domain.{Group, LoggedUser}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.utils.ScalaOps._

abstract class BaseAuthorizationRule
  extends AuthorizationRule {

  protected def authorize(requestContext: RequestContext,
                          blockContext: BlockContext,
                          user: LoggedUser): Task[AuthorizationResult]

  override def check(requestContext: RequestContext, blockContext: BlockContext): Task[Rule.RuleResult] = {
    blockContext.loggedUser match {
      case Some(user) =>
        authorize(requestContext, blockContext, user)
          .map {
            case AuthorizationResult.Unauthorized =>
              Rejected
            case AuthorizationResult.Authorized(currentGroup, availableGroups) =>
              Fulfilled {
                blockContext
                  .withCurrentGroup(currentGroup)
                  .withAddedAvailableGroups(availableGroups)
              }
          }
      case None =>
        Task.now(Rejected)
    }
  }

  protected def pickCurrentGroupFrom(resolvedGroups: NonEmptySet[Group]): Group = {
    resolvedGroups.toSortedSet.toList.minBy(_.value)
  }
}

object BaseAuthorizationRule {
  trait AuthorizationResult
  object AuthorizationResult {
    case object Unauthorized extends AuthorizationResult
    final case class Authorized(currentGroup: Group, availableGroups: NonEmptySet[Group]) extends AuthorizationResult
  }
}
