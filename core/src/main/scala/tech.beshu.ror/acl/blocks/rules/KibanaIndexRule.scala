package tech.beshu.ror.acl.blocks.rules

import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.KibanaIndexRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.MatchingAlwaysRule
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.IndexName
import tech.beshu.ror.commons.domain.Value

class KibanaIndexRule(settings: Settings)
  extends MatchingAlwaysRule {

  override val name: Rule.Name = Rule.Name("kibana_hide_apps")

  override def process(requestContext: RequestContext,
                       blockContext: BlockContext): Task[BlockContext] = Task.now {
    settings
      .kibanaIndex
      .getValue(requestContext)
      .map(blockContext.setKibanaIndex(_))
      .getOrElse(blockContext)
  }
}

object KibanaIndexRule {

  final case class Settings(kibanaIndex: Value[IndexName])

}
