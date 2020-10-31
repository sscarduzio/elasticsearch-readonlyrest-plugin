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
import tech.beshu.ror.accesscontrol.blocks.rules.ResponseFieldsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.FieldsRestrictions.AccessMode
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.domain.{DocumentField, FieldsRestrictions, Header}
import tech.beshu.ror.accesscontrol.headerValues.transientFieldsToHeaderValue
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class ResponseFieldsRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = ResponseFieldsRule.name

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    val maybeResolvedFields = resolveAll(settings.response_fields.toNonEmptyList, blockContext)
    UniqueNonEmptyList.fromList(maybeResolvedFields) match {
      case Some(resolvedFields) =>
        val transientFieldsHeader = new Header(
          Name.transientResponseFields,
          transientFieldsToHeaderValue.toRawValue(FieldsRestrictions(resolvedFields, settings.accessMode))
        )
        RuleResult.Fulfilled(blockContext.withAddedContextHeader(transientFieldsHeader))
      case _ =>
        RuleResult.Rejected()
    }
  }

}

object ResponseFieldsRule {
  val name = Rule.Name("response_fields")

  final case class Settings(response_fields: UniqueNonEmptyList[RuntimeMultiResolvableVariable[DocumentField]],
                            accessMode: AccessMode)
}
