package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.aDomain.Address
import tech.beshu.ror.acl.blocks.rules.LocalHostsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.request.RequestContext

class LocalHostsRule(val settings: Settings)
  extends BaseHostsRule {

  override val name: Rule.Name = LocalHostsRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = {
    checkAllowedAddresses(requestContext, blockContext)(
      allowedAddresses = settings.allowedAddresses,
      addressToCheck = requestContext.localAddress
    ).map(condition => RuleResult.fromCondition(blockContext)(condition))
  }

}

object LocalHostsRule {
  val name = Rule.Name("hosts_local")

  final case class Settings(allowedAddresses: NonEmptySet[Value[Address]])

}