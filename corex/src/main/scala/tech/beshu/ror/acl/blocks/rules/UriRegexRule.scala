package tech.beshu.ror.acl.blocks.rules

import java.util.regex.Pattern

import monix.eval.Task
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.acl.blocks.rules.UriRegexRule.Settings
import tech.beshu.ror.acl.request.RequestContext

class UriRegexRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = UriRegexRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task {
    RuleResult.fromCondition(blockContext) {
      settings
        .uriPattern
        .getValue(requestContext.variablesResolver, blockContext)
        .exists {
          _.matcher(requestContext.uriPath.value).find()
        }
    }
  }
}

object UriRegexRule {
  val name = Rule.Name("uri_re")

  final case class Settings(uriPattern: Value[Pattern])

}
