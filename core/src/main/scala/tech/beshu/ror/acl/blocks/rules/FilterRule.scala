package tech.beshu.ror.acl.blocks.rules

import monix.eval.Task
import tech.beshu.ror.commons.aDomain.Header.Name
import tech.beshu.ror.commons.aDomain.{Filter, Header}
import tech.beshu.ror.commons.headerValues.transientFilterHeaderValue
import tech.beshu.ror.acl.blocks.Value.Unresolvable
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.blocks.rules.FilterRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.acl.request.RequestContext

/**
  * Document level security (DLS) rule.
  */
class FilterRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = FilterRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task.now {
    if (requestContext.isReadOnlyRequest) Rejected
    else {
      settings.filter.getValue(requestContext.variablesResolver, blockContext) match {
        case Left(_: Unresolvable) =>
          Rejected
        case Right(filter) =>
          Fulfilled(blockContext.addContextHeader(Header(Name.transientFilter, filter)))
      }
    }
  }
}

object FilterRule {
  val name = Rule.Name("filter")

  final case class Settings(filter: Value[Filter])
}
