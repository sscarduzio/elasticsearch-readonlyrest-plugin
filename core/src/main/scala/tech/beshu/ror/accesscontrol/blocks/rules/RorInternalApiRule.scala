package tech.beshu.ror.accesscontrol.blocks.rules

import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext._
import tech.beshu.ror.accesscontrol.blocks.rules.RorInternalApiRule.InternalApiAccess.{Allow, Forbid}
import tech.beshu.ror.accesscontrol.blocks.rules.RorInternalApiRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RegularRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.utils.IndicesMatcher
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.{RorAuditIndexTemplate, RorConfigurationIndex}

class RorInternalApiRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = RorInternalApiRule.name

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] = Task.now {
    val isInternalApiRelatedRequest = isRelatedToRorInternals(blockContext)
    settings.access match {
      case Allow => Fulfilled(blockContext)
      case Forbid if !isInternalApiRelatedRequest => Fulfilled(blockContext)
      case Forbid => Rejected()
    }
  }

  private def isRelatedToRorInternals(blockContext: BlockContext) = {
    rorAction(blockContext) || relatedToAuditIndex(blockContext) || relatedToConfigurationIndex(blockContext)
  }

  private def rorAction(blockContext: BlockContext) =
    blockContext.requestContext.action.isRorInternalAction

  private def relatedToAuditIndex(blockContext: BlockContext) = settings.indexAuditTemplate match {
    case Some(indexAuditTemplate) =>
      blockContext
        .allUsedIndices
        .exists(indexAuditTemplate.conforms)
    case None =>
      false
  }

  private def relatedToConfigurationIndex(blockContext: BlockContext) =
    IndicesMatcher
      .create(blockContext.allUsedIndices)
      .`match`(settings.configurationIndex.index)

}

object RorInternalApiRule {
  val name: Rule.Name = Rule.Name("ror_internal_api")

  final case class Settings(access: InternalApiAccess,
                            configurationIndex: RorConfigurationIndex,
                            indexAuditTemplate: Option[RorAuditIndexTemplate])

  sealed trait InternalApiAccess
  object InternalApiAccess {
    case object Allow extends InternalApiAccess
    case object Forbid extends InternalApiAccess
  }
}