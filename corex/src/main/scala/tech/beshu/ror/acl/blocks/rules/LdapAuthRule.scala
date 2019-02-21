package tech.beshu.ror.acl.blocks.rules

import cats.data.EitherT
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.Fulfilled
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule, RuleResult}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.utils.RuleResultOps._
import tech.beshu.ror.acl.utils.ScalaOps._

class LdapAuthRule(authentication: LdapAuthorizationRule,
                   authorization: LdapAuthorizationRule)
  extends AuthenticationRule with AuthorizationRule {

  override val name: Rule.Name = LdapAuthRule.name

  override def check(requestContext: RequestContext, blockContext: BlockContext): Task[RuleResult] =
    value {
      for {
        Fulfilled(modifiedBlockContext) <- EitherT(authentication.check(requestContext, blockContext).map(_.toEither))
        Fulfilled(finalBlockContext) <- EitherT(authorization.check(requestContext, modifiedBlockContext).map(_.toEither))
      } yield Fulfilled(finalBlockContext)
    } map (_.toRuleResult)
}

object LdapAuthRule {
  val name = Rule.Name("ldap_auth")
}