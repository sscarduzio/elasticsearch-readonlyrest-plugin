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
    settings.access match {
      case Allow => Fulfilled(blockContext)
      case Forbid if isRelatedToRorInternals(blockContext) => Rejected()
      case Forbid => Fulfilled(blockContext)
    }
  }

  private def isRelatedToRorInternals(blockContext: BlockContext) = {
    rorAction(blockContext) || isWriteActionRelatedToRorIndices(blockContext)
  }

  private def isWriteActionRelatedToRorIndices(blockContext: BlockContext) = {
    !blockContext.requestContext.isReadOnlyRequest && (relatedToAuditIndex(blockContext) || relatedToConfigurationIndex(blockContext))
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