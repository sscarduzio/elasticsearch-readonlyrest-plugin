/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.accesscontrol.blocks.postprocessing

import eu.timepit.refined.auto._
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.postprocessing.BlockPostProcessingCheck.{Name, PostProcessingResult}
import tech.beshu.ror.accesscontrol.blocks.rules.RorInternalApiRule
import tech.beshu.ror.accesscontrol.blocks.rules.RorInternalApiRule.InternalApiAccess.Forbid
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings

class ImplicitRorInternalApiCallCheck(block: Block,
                                      settings: GlobalSettings)
  extends BlockPostProcessingCheck {

  override val name: Name = BlockPostProcessingCheck.Name("ROR internal API guard")

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
