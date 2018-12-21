package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.LocalHostsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.{RuleResult, RegularRule}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.Address
import tech.beshu.ror.commons.domain.Value

class LocalHostsRule(settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = Rule.Name("hosts_local")

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task.now {
    RuleResult.fromCondition(blockContext) {
      settings
        .allowedAddresses
        .toSortedSet
        .flatMap(_.getValue(requestContext))
        .contains(requestContext.localAddress)
    }
  }
}

object LocalHostsRule {

  final case class Settings(allowedAddresses: NonEmptySet[Value[Address]])

}