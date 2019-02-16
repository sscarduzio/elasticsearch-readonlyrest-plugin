package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.aDomain.Address
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.Rejected
import tech.beshu.ror.acl.blocks.rules.XForwardedForRule.Settings
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._

class XForwardedForRule(val settings: Settings)
  extends BaseHostsRule {

  override val name: Rule.Name = XForwardedForRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = {
    requestContext.xForwardedForHeaderValue match {
      case Some(xForwardedForAddress)  =>
        checkAllowedAddresses(requestContext, blockContext)(
          allowedAddresses = settings.allowedAddresses,
          addressToCheck = xForwardedForAddress
        ).map(condition => RuleResult.fromCondition(blockContext)(condition))
      case None =>
        Task.now(Rejected)
    }
  }

}

object XForwardedForRule {
  val name = Rule.Name("x_forwarded_for")

  final case class Settings(allowedAddresses: NonEmptySet[Value[Address]])

}
