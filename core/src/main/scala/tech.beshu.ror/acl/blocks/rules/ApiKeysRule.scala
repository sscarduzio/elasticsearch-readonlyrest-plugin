package tech.beshu.ror.acl.blocks.rules

import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.ApiKeysRule.{Settings, xApiKeyHeaderName}
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.requestcontext.RequestContext

class ApiKeysRule(settings: Settings)
  extends RegularRule {

  override def `match`(context: RequestContext): Task[Boolean] = Task.now {
    context
      .getHeaders
      .find(_.name == xApiKeyHeaderName)
      .exists { header => settings.apiKeys.contains(header.value) }
  }
}

object ApiKeysRule {

  final case class Settings(apiKeys: Set[String])

  val xApiKeyHeaderName: String = "X-Api-Key"
}