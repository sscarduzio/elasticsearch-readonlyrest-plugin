package tech.beshu.ror.acl.blocks.rules

import monix.eval.Task
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.blocks.rules.KibanaIndexRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.MatchingAlwaysRule
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.IndexName

class KibanaIndexRule(settings: Settings)
  extends MatchingAlwaysRule {

  override val name: Rule.Name = KibanaIndexRule.name

  override def process(requestContext: RequestContext,
                       blockContext: BlockContext): Task[BlockContext] = Task.now {
    settings
      .kibanaIndex
      .getValue(requestContext.variablesResolver, blockContext)
      .map(blockContext.setKibanaIndex)
      .getOrElse(blockContext)
  }
}

object KibanaIndexRule {
  val name = Rule.Name("kibana_index")

  final case class Settings(kibanaIndex: Value[IndexName])
}
