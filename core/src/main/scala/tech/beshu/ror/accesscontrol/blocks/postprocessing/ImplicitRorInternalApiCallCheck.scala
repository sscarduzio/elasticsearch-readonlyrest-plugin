package tech.beshu.ror.accesscontrol.blocks.postprocessing

import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.postprocessing.BlockPostProcessingCheck.PostProcessingResult
import tech.beshu.ror.accesscontrol.blocks.rules.RorInternalApiRule
import tech.beshu.ror.accesscontrol.blocks.rules.RorInternalApiRule.InternalApiAccess.Forbid
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings

class ImplicitRorInternalApiCallCheck(block: Block,
                                      settings: GlobalSettings)
  extends BlockPostProcessingCheck {

  override val name: String = "ROR internal API guard"

  private val internalRule = new RorInternalApiRule(RorInternalApiRule.Settings(
    access = Forbid,
    configurationIndex = settings.configurationIndex,
    indexAuditTemplate = settings.indexAuditTemplate
  ))

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[PostProcessingResult] = {
    if (containsRorInternalApiRule(block)) {
      Task.now(PostProcessingResult.Continue)
    } else {
      internalRule
        .check(blockContext)
        .map {
          case RuleResult.Fulfilled(_) => PostProcessingResult.Continue
          case RuleResult.Rejected(_) => PostProcessingResult.Reject
        }
    }
  }

  private def containsRorInternalApiRule(block: Block) = {
    block.rules.collect { case _: RorInternalApiRule => () }.nonEmpty
  }
}
