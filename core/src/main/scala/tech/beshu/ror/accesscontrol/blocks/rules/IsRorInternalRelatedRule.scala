package tech.beshu.ror.accesscontrol.blocks.rules

import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext._
import tech.beshu.ror.accesscontrol.blocks.rules.IsRorInternalRelatedRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RegularRule
import tech.beshu.ror.accesscontrol.blocks.rules.utils.IndicesMatcher
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.{RorAuditIndexTemplate, RorConfigurationIndex}

class IsRorInternalRelatedRule(settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = IsRorInternalRelatedRule.name

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] = {
    Task.now {
      if (isRelatedToRorInternals(blockContext) == settings.isRorInternal) Rule.RuleResult.Fulfilled(blockContext)
      else Rule.RuleResult.Rejected()
    }
  }

  private def isRelatedToRorInternals(blockContext: BlockContext) = {
    rorAction(blockContext) || relatedToAuditIndex(blockContext) || relatedToConfigurationIndex(blockContext)
  }

  private def rorAction(blockContext: BlockContext) =
    blockContext.requestContext.action.isRorInternalAction

  private def relatedToAuditIndex(blockContext: BlockContext) =
    blockContext
      .allUsedIndices
      .exists(settings.indexAuditTemplate.conforms)

  private def relatedToConfigurationIndex(blockContext: BlockContext) =
    IndicesMatcher
      .create(blockContext.allUsedIndices)
      .`match`(settings.configurationIndex.index)

}

object IsRorInternalRelatedRule {
  val name: Rule.Name = Rule.Name("ror_internals")

  final case class Settings(isRorInternal: Boolean,
                            configurationIndex: RorConfigurationIndex,
                            indexAuditTemplate: RorAuditIndexTemplate)
}