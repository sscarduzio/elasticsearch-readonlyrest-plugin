package tech.beshu.ror.acl.blocks.rules

import java.util.regex.Pattern

import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.Rule.{RuleResult, RegularRule}
import tech.beshu.ror.acl.blocks.rules.UriRegexRule.Settings
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.domain.Value

class UriRegexRule(settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = Rule.Name("uri_re")

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task.now {
    RuleResult.fromCondition(blockContext) {
      settings
        .uriPattern
        .getValue(requestContext)
        .exists {
          _.matcher(requestContext.uri.toString()).find()
        }
    }
  }
}

object UriRegexRule {

  final case class Settings(uriPattern: Value[Pattern])

}
