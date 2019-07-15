package tech.beshu.ror.acl.blocks.rules.impersonation

import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.Rule
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.acl.request.RequestContext

class ImpersonationRuleDecorator(underlying: AuthenticationRule with Impersonation) extends AuthenticationRule {
  override val name: Rule.Name = underlying.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[Rule.RuleResult] = {
    ???
  }
}
