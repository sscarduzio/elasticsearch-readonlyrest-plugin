package tech.beshu.ror.acl.blocks.rules

import java.util.regex.Pattern
import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.blocks.rules.UriRegexRule.Settings
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.domain.Value

class UriRegexRule(settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = Rule.Name("uri_re")

  override def `match`(context: RequestContext): Task[Boolean] = Task.now {
    settings
      .uriPattern
      .getValue(context)
      .exists {
        _.matcher(context.uri.toString()).find()
      }
  }
}

object UriRegexRule {

  final case class Settings(uriPattern: Value[Pattern])

}
