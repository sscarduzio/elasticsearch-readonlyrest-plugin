package tech.beshu.ror.acl.blocks.rules

import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.KibanaIndexRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.requestcontext.RequestContext
import tech.beshu.ror.commons.aDomain.IndexName
import tech.beshu.ror.commons.domain.Value

class KibanaIndexRule(settings: Settings)
  extends RegularRule {

  override def `match`(context: RequestContext): Task[Boolean] = Task.now {
    settings
      .kibanaIndex
      .getValue(context)
      .foreach { index =>
        context.setKibanaIndex(index)
      }
    true
  }
}

object KibanaIndexRule {
  final case class Settings(kibanaIndex: Value[IndexName])
}
