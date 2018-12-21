package tech.beshu.ror.acl.blocks.rules

import monix.eval.Task
import squants.information.Information
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.MaxBodyLengthRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.{RuleResult, RegularRule}
import tech.beshu.ror.acl.request.RequestContext

class MaxBodyLengthRule(settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = Rule.Name("max_body_length")

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task.now {
    RuleResult.fromCondition(blockContext) {
      requestContext.contentLength <= settings.maxContentLength
    }
  }
}

object MaxBodyLengthRule {

  final case class Settings(maxContentLength: Information)
}