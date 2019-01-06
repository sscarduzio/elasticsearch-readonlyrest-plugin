package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.blocks.rules.LocalHostsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.Address

class LocalHostsRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = LocalHostsRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task.now {
    RuleResult.fromCondition(blockContext) {
      settings
        .allowedAddresses
        .toSortedSet
        .flatMap(_.getValue(requestContext.variablesResolver, blockContext))
        .contains(requestContext.localAddress)
    }
  }
}

object LocalHostsRule {
  val name = Rule.Name("hosts_local")

  final case class Settings(allowedAddresses: NonEmptySet[Value[Address]])
}