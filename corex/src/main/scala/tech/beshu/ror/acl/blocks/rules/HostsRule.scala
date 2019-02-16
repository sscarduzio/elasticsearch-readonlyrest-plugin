package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.aDomain.Address
import tech.beshu.ror.acl.blocks.rules.HostsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.request.RequestContextOps._

class HostsRule(val settings: Settings)
  extends BaseHostsRule {

  override val name: Rule.Name = HostsRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = {
    requestContext.xForwardedForHeaderValue match {
      case Some(xForwardedHeaderValue) if settings.acceptXForwardedForHeader =>
        checkAllowedAddresses(requestContext, blockContext)(
          allowedAddresses = settings.allowedHosts,
          addressToCheck = xForwardedHeaderValue
        ).flatMap {
            case true =>
              Task.now(RuleResult.Fulfilled(blockContext))
            case false =>
              checkAllowedAddresses(requestContext, blockContext)(
                allowedAddresses = settings.allowedHosts,
                addressToCheck = requestContext.remoteAddress
              ).map(condition => RuleResult.fromCondition(blockContext)(condition))
          }
      case _ =>
        checkAllowedAddresses(requestContext, blockContext)(
          allowedAddresses = settings.allowedHosts,
          addressToCheck = requestContext.remoteAddress
        ).map(condition => RuleResult.fromCondition(blockContext)(condition))
    }
  }
}

object HostsRule {

  val name: Rule.Name = Rule.Name("hosts")

  final case class Settings(allowedHosts: NonEmptySet[Value[Address]],
                            acceptXForwardedForHeader: Boolean)

}
