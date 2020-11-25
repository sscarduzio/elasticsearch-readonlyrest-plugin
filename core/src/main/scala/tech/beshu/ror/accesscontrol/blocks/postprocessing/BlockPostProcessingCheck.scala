package tech.beshu.ror.accesscontrol.blocks.postprocessing

import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.postprocessing.BlockPostProcessingCheck.PostProcessingResult
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}

trait BlockPostProcessingCheck {

  def name: String
  def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[PostProcessingResult]
}

object BlockPostProcessingCheck {

  sealed trait PostProcessingResult
  object PostProcessingResult {
    case object Continue extends PostProcessingResult
    case object Reject extends PostProcessingResult
  }
}