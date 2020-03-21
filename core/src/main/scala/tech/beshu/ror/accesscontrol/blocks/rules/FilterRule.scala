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
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.FilterRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Unresolvable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.domain.{Filter, Header, Operation}
import tech.beshu.ror.accesscontrol.headerValues.transientFilterHeaderValue
import tech.beshu.ror.accesscontrol.request.RequestContext

/**
  * Document level security (DLS) rule.
  */
class FilterRule(val settings: Settings)
  extends RegularRule with Logging {

  override val name: Rule.Name = FilterRule.name

  override def check[T <: Operation](requestContext: RequestContext[T],
                                     blockContext: BlockContext[T]): Task[RuleResult[T]] = Task {
    if (!requestContext.isAllowedForDLS) Rejected()
    else {
      settings.filter.resolve(requestContext, blockContext) match {
        case Left(_: Unresolvable) =>
          Rejected()
        case Right(filter) =>
          Fulfilled(blockContext.withAddedContextHeader(Header(Name.transientFilter, filter)))
      }
    }
  }
}

object FilterRule {
  val name = Rule.Name("filter")

  final case class Settings(filter: RuntimeSingleResolvableVariable[Filter])
}
