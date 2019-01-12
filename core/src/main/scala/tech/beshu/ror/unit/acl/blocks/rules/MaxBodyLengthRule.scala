package tech.beshu.ror.unit.acl.blocks.rules

import monix.eval.Task
import squants.information.Information
import tech.beshu.ror.unit.acl.blocks.BlockContext
import tech.beshu.ror.unit.acl.blocks.rules.MaxBodyLengthRule.Settings
import tech.beshu.ror.unit.acl.blocks.rules.Rule.{RuleResult, RegularRule}
import tech.beshu.ror.unit.acl.request.RequestContext

class MaxBodyLengthRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = MaxBodyLengthRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task.now {
    RuleResult.fromCondition(blockContext) {
      requestContext.contentLength <= settings.maxContentLength
    }
  }
}

object MaxBodyLengthRule {
  val name = Rule.Name("max_body_length")

  final case class Settings(maxContentLength: Information)
}