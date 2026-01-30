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
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.*
import tech.beshu.ror.accesscontrol.blocks.BlockContextWithFilterUpdater.{FilterableBlockContextWithFilterUpdater, FilterableMultiRequestBlockContextWithFilterUpdater}
import tech.beshu.ror.accesscontrol.blocks.Decision.Permitted
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleName}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.FilterRule.Settings
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Unresolvable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, BlockContextWithFilterUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.Filter

/**
  * Document level security (DLS) rule.
  */
class FilterRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = FilterRule.Name.name

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = Task {
    blockContext.requestContext match {
      case r if !r.isAllowedForDLS => reject()
      case r if r.action.isRorAction => reject()
      case _ =>
        settings.filter.resolve(blockContext) match {
          case Left(_: Unresolvable) =>
            reject()
          case Right(filter) =>
            BlockContextUpdater[B] match {
              case CurrentUserMetadataRequestBlockContextUpdater => Permitted(blockContext)
              case GeneralNonIndexRequestBlockContextUpdater => Permitted(blockContext)
              case RepositoryRequestBlockContextUpdater => Permitted(blockContext)
              case SnapshotRequestBlockContextUpdater => Permitted(blockContext)
              case DataStreamRequestBlockContextUpdater => Permitted(blockContext)
              case TemplateRequestBlockContextUpdater => Permitted(blockContext)
              case GeneralIndexRequestBlockContextUpdater => Permitted(blockContext)
              case AliasRequestBlockContextUpdater => Permitted(blockContext)
              case MultiIndexRequestBlockContextUpdater => Permitted(blockContext)
              case FilterableRequestBlockContextUpdater => addFilter(blockContext, filter)
              case FilterableMultiRequestBlockContextUpdater => addFilter(blockContext, filter)
              case RorApiRequestBlockContextUpdater => Decision.Permitted(blockContext)
            }
        }
    }
  }

  private def addFilter[B <: BlockContext : BlockContextWithFilterUpdater](blockContext: B,
                                                                           filter: Filter) = {
    Permitted(blockContext.withFilter(filter))
  }

  private def reject[T]() = Decision.Denied[T](Cause.NotAuthorized)
}

object FilterRule {

  implicit case object Name extends RuleName[FilterRule] {
    override val name = Rule.Name("filter")
  }

  final case class Settings(filter: RuntimeSingleResolvableVariable[Filter])

}
