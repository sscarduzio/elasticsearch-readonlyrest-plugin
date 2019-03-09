package tech.beshu.ror.acl.blocks.rules

import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule, RuleResult}
import tech.beshu.ror.acl.request.RequestContext

class LdapAuthRule(val authentication: LdapAuthenticationRule,
                   val authorization: LdapAuthorizationRule)
  extends AuthenticationRule with AuthorizationRule {

  override val name: Rule.Name = LdapAuthRule.name

  override def check(requestContext: RequestContext, blockContext: BlockContext): Task[RuleResult] = {
    authentication
      .check(requestContext, blockContext)
      .flatMap {
        case Fulfilled(modifiedBlockContext) =>
          authorization.check(requestContext, modifiedBlockContext)
        case Rejected =>
          Task.now(Rejected)
      }
  }
}

object LdapAuthRule {
  val name = Rule.Name("ldap_auth")
}