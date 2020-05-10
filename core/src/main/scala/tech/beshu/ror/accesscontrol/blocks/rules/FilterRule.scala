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
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.{CurrentUserMetadataRequestBlockContextUpdater, GeneralIndexRequestBlockContextUpdater, GeneralNonIndexRequestBlockContextUpdater, MultiIndexRequestBlockContextUpdater, RepositoryRequestBlockContextUpdater, SnapshotRequestBlockContextUpdater, TemplateRequestBlockContextUpdater}
import tech.beshu.ror.accesscontrol.blocks.rules.FilterRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Unresolvable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.Filter

/**
  * Document level security (DLS) rule.
  */
class FilterRule(val settings: Settings)
  extends RegularRule with Logging {

  override val name: Rule.Name = FilterRule.name

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    val requestContext = blockContext.requestContext
    if (!requestContext.isAllowedForDLS) Rejected()
    else {
      settings.filter.resolve(blockContext) match {
        case Left(_: Unresolvable) =>
          Rejected()
        case Right(filter) =>
          BlockContextUpdater[B] match {
            case CurrentUserMetadataRequestBlockContextUpdater => Fulfilled(blockContext)
            case GeneralNonIndexRequestBlockContextUpdater => Fulfilled(blockContext)
            case RepositoryRequestBlockContextUpdater => Fulfilled(blockContext)
            case SnapshotRequestBlockContextUpdater => Fulfilled(blockContext)
            case updater@GeneralIndexRequestBlockContextUpdater => Fulfilled(updater.withFilter(blockContext, filter))
            case updater@MultiIndexRequestBlockContextUpdater => Fulfilled(updater.withFilter(blockContext, filter))
            case TemplateRequestBlockContextUpdater => Fulfilled(blockContext)
          }
      }
    }
  }
}

object FilterRule {
  val name = Rule.Name("filter")

  final case class Settings(filter: RuntimeSingleResolvableVariable[Filter])
}
