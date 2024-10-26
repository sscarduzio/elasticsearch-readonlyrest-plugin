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
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.ResponseFieldsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, FilteredResponseFields}
import tech.beshu.ror.accesscontrol.domain.ResponseFieldsFiltering.*
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class ResponseFieldsRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = ResponseFieldsRule.Name.name

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    val maybeResolvedFields = resolveAll(settings.responseFields.toNonEmptyList, blockContext)
    UniqueNonEmptyList.fromIterable(maybeResolvedFields) match {
      case Some(resolvedFields) =>
        RuleResult.Fulfilled(blockContext.withAddedResponseTransformation(FilteredResponseFields(ResponseFieldsRestrictions(resolvedFields, settings.accessMode))))
      case None =>
        RuleResult.Rejected()
    }
  }
}

object ResponseFieldsRule {

  implicit case object Name extends RuleName[ResponseFieldsRule] {
    override val name = Rule.Name("response_fields")
  }

  final case class Settings(responseFields: UniqueNonEmptyList[RuntimeMultiResolvableVariable[ResponseField]],
                            accessMode: AccessMode)
}
