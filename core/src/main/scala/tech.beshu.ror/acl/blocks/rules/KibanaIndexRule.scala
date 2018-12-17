package tech.beshu.ror.acl.blocks.rules

import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.KibanaIndexRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.MatchingAlwaysRule
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.IndexName
import tech.beshu.ror.commons.domain.Value

class KibanaIndexRule(settings: Settings)
  extends MatchingAlwaysRule {

  override def process(context: RequestContext): Task[Unit] = Task.now {
    settings
      .kibanaIndex
      .getValue(context)
      .foreach { index =>
        context.setKibanaIndex(index)
      }
  }
}

object KibanaIndexRule {
  final case class Settings(kibanaIndex: Value[IndexName])
}
