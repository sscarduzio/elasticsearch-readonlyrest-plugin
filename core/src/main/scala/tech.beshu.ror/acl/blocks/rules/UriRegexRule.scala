package tech.beshu.ror.acl.blocks.rules

import java.util.regex.Pattern

import monix.eval.Task
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.acl.blocks.rules.UriRegexRule.Settings
import tech.beshu.ror.acl.request.RequestContext

class UriRegexRule(settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = Rule.Name("uri_re")

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task.now {
    RuleResult.fromCondition(blockContext) {
      settings
        .uriPattern
        .getValue(requestContext.variablesResolver, blockContext)
        .exists {
          _.matcher(requestContext.uri.toString()).find()
        }
    }
  }
}

object UriRegexRule {

  final case class Settings(uriPattern: Value[Pattern])

}
