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
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.{AliasRequestBlockContextUpdater, CurrentUserMetadataRequestBlockContextUpdater, FilterableMultiRequestBlockContextUpdater, FilterableRequestBlockContextUpdater, GeneralIndexRequestBlockContextUpdater, GeneralNonIndexRequestBlockContextUpdater, MultiIndexRequestBlockContextUpdater, RepositoryRequestBlockContextUpdater, SnapshotRequestBlockContextUpdater, TemplateRequestBlockContextUpdater}
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Fulfilled
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, BlockContextWithFieldsUpdater}
import tech.beshu.ror.accesscontrol.domain.FieldsRestrictions.AccessMode
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.domain.{DocumentField, FieldsRestrictions, Header}
import tech.beshu.ror.accesscontrol.headerValues.transientFieldsToHeaderValue
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class FieldsRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = FieldsRule.name

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    if (!blockContext.requestContext.isReadOnlyRequest)
      RuleResult.Rejected()
    else {
      val maybeResolvedFields = resolveAll(settings.fields.toNonEmptyList, blockContext)
      UniqueNonEmptyList.fromList(maybeResolvedFields) match {
        case Some(resolvedFields) =>
          val fieldsRestrictions = FieldsRestrictions(resolvedFields, settings.accessMode)

          //hybrid approach
          if (blockContext.requestContext.requiresContextHeaderForFLS) {
            val transientFieldsHeader = new Header(
              Name.transientFields,
              transientFieldsToHeaderValue.toRawValue(FieldsRestrictions(resolvedFields, settings.accessMode))
            )
            RuleResult.Fulfilled(blockContext.withAddedContextHeader(transientFieldsHeader))
          } else {
            BlockContextUpdater[B] match {
              case CurrentUserMetadataRequestBlockContextUpdater => Fulfilled(blockContext)
              case GeneralNonIndexRequestBlockContextUpdater => Fulfilled(blockContext)
              case RepositoryRequestBlockContextUpdater => Fulfilled(blockContext)
              case SnapshotRequestBlockContextUpdater => Fulfilled(blockContext)
              case TemplateRequestBlockContextUpdater => Fulfilled(blockContext)
              case GeneralIndexRequestBlockContextUpdater => Fulfilled(blockContext)
              case MultiIndexRequestBlockContextUpdater => Fulfilled(blockContext)
              case AliasRequestBlockContextUpdater => Fulfilled(blockContext)
              case FilterableRequestBlockContextUpdater => addFields(blockContext, fieldsRestrictions)
              case FilterableMultiRequestBlockContextUpdater => addFields(blockContext, fieldsRestrictions)
            }
          }
        case _ =>
          RuleResult.Rejected()
      }
    }
  }

  private def addFields[B <: BlockContext : BlockContextWithFieldsUpdater](blockContext: B,
                                                                           fields: FieldsRestrictions) = {
    Fulfilled(blockContext.withFields(fields))
  }
}

object FieldsRule {
  val name = Rule.Name("fields")

  final case class Settings(fields: UniqueNonEmptyList[RuntimeMultiResolvableVariable[DocumentField]],
                            accessMode: AccessMode)

}
