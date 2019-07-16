package tech.beshu.ror.acl.blocks.rules.impersonation

import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.domain.LoggedUser
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._

class ImpersonationRuleDecorator(underlying: AuthenticationRule with ImpersonationSupport) extends AuthenticationRule {
  override val name: Rule.Name = underlying.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[Rule.RuleResult] = {
    requestContext.impersonateAs match {
      case Some(userId) =>
        underlying
          .exists(userId)
          .map {
            case true =>
              Fulfilled(blockContext.withLoggedUser(LoggedUser(userId)))
            case false =>
              Rejected
          }
      case None =>
        underlying.check(requestContext, blockContext)
    }
  }
}
