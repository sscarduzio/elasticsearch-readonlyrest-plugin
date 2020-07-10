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

import cats.data.NonEmptySet
import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.DocumentField.{ADocumentField, NegatedDocumentField}
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.domain.{DocumentField, Header}
import tech.beshu.ror.accesscontrol.headerValues.transientFieldsToHeaderValue
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.SortedSet

class FieldsRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = FieldsRule.name

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    if (!blockContext.requestContext.isReadOnlyRequest) RuleResult.Rejected()
    else {
      val resolved = resolveAll(settings.fields.toNonEmptyList, blockContext)
      val option = NonEmptySet.fromSet(SortedSet.empty[DocumentField] ++ resolved.toSet)
      option match {
        case Some(resolvedFields) =>
          val transientFieldsHeader = new Header(
            Name.transientFields,
            transientFieldsToHeaderValue.toRawValue(resolvedFields)
          )
          RuleResult.Fulfilled(blockContext.withAddedContextHeader(transientFieldsHeader))
        case None =>
          RuleResult.Rejected()
      }
    }
  }
}

object FieldsRule {
  val name = Rule.Name("fields")

  final case class Settings private(fields: NonEmptySet[RuntimeMultiResolvableVariable[DocumentField]])

  object Settings {
    def ofFields(fields: NonEmptySet[RuntimeMultiResolvableVariable[ADocumentField]]): Settings = Settings(fields.widen[RuntimeMultiResolvableVariable[DocumentField]])

    def ofNegatedFields(fields: NonEmptySet[RuntimeMultiResolvableVariable[NegatedDocumentField]]): Settings = Settings(fields.widen[RuntimeMultiResolvableVariable[DocumentField]])
  }
}
