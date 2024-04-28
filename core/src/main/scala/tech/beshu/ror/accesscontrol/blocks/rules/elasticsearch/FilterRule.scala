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
package tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch

import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater._
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.FilterRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Unresolvable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, BlockContextWithFilterUpdater}
import tech.beshu.ror.accesscontrol.domain.Filter
import tech.beshu.ror.accesscontrol.blocks.BlockContextWithFilterUpdater.FilterableBlockContextWithFilterUpdater
import tech.beshu.ror.accesscontrol.blocks.BlockContextWithFilterUpdater.FilterableMultiRequestBlockContextWithFilterUpdater

/**
  * Document level security (DLS) rule.
  */
class FilterRule(val settings: Settings)
  extends RegularRule with Logging {

  override val name: Rule.Name = FilterRule.Name.name

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    blockContext.requestContext match {
      case r if !r.isAllowedForDLS => Rejected()
      case r if r.action.isRorAction => Rejected()
      case _ =>
        settings.filter.resolve(blockContext) match {
          case Left(_: Unresolvable) =>
            Rejected()
          case Right(filter) =>
            BlockContextUpdater[B] match {
              case CurrentUserMetadataRequestBlockContextUpdater => Fulfilled(blockContext)
              case GeneralNonIndexRequestBlockContextUpdater => Fulfilled(blockContext)
              case RepositoryRequestBlockContextUpdater => Fulfilled(blockContext)
              case SnapshotRequestBlockContextUpdater => Fulfilled(blockContext)
              case DataStreamRequestBlockContextUpdater => Fulfilled(blockContext)
              case TemplateRequestBlockContextUpdater => Fulfilled(blockContext)
              case GeneralIndexRequestBlockContextUpdater => Fulfilled(blockContext)
              case AliasRequestBlockContextUpdater => Fulfilled(blockContext)
              case MultiIndexRequestBlockContextUpdater => Fulfilled(blockContext)
              case FilterableRequestBlockContextUpdater => addFilter(blockContext, filter)
              case FilterableMultiRequestBlockContextUpdater => addFilter(blockContext, filter)
              case RorApiRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
            }
        }
    }
  }

  private def addFilter[B <: BlockContext : BlockContextWithFilterUpdater](blockContext: B,
                                                                           filter: Filter) = {
    Fulfilled(blockContext.withFilter(filter))
  }
}

object FilterRule {

  implicit case object Name extends RuleName[FilterRule] {
    override val name = Rule.Name("filter")
  }

  final case class Settings(filter: RuntimeSingleResolvableVariable[Filter])

}
