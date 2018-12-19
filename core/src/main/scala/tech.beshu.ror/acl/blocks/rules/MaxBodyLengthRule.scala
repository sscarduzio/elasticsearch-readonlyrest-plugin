package tech.beshu.ror.acl.blocks.rules

import monix.eval.Task
import squants.information.Information
import tech.beshu.ror.acl.blocks.rules.MaxBodyLengthRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.request.RequestContext

class MaxBodyLengthRule(settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = Rule.Name("max_body_length")

  override def `match`(context: RequestContext): Task[Boolean] = Task.now {
    context.contentLength <= settings.maxContentLength
  }
}

object MaxBodyLengthRule {

  final case class Settings(maxContentLength: Information)
}