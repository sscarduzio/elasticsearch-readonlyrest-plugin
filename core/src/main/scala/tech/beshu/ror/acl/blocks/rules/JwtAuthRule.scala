package tech.beshu.ror.acl.blocks.rules

import monix.eval.Task
import tech.beshu.ror.acl.aDomain.Group
import tech.beshu.ror.acl.blocks.definitions.JwtDef
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule, RuleResult}
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.request.RequestContext

class JwtAuthRule(val settings: JwtAuthRule.Settings)
  extends AuthenticationRule
    with AuthorizationRule {

  override val name: Rule.Name = ExternalAuthenticationRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = ???
}

object JwtAuthRule {
  val name = Rule.Name("jwt_auth")

  final case class Settings(jwt: JwtDef, groups: Set[Value[Group]])
}