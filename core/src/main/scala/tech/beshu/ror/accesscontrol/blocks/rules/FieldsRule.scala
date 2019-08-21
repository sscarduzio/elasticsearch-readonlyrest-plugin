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

import cats.implicits._
import cats.data.NonEmptySet
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.domain.DocumentField.{ADocumentField, NegatedDocumentField}
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.domain.{DocumentField, Header}
import tech.beshu.ror.accesscontrol.show.logs._

class FieldsRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = FieldsRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task {
    if(!requestContext.isReadOnlyRequest) RuleResult.Rejected()
    else RuleResult.Fulfilled(blockContext.withAddedContextHeader(transientFieldsHeader))
  }

  private val transientFieldsHeader = new Header(
    Name.transientFields,
    NonEmptyString.unsafeFrom(settings.fields.map(_.show).mkString(","))
  )
}

object FieldsRule {
  val name = Rule.Name("fields")

  final case class Settings private(fields: Set[DocumentField])
  object Settings {
    def ofFields(fields: NonEmptySet[ADocumentField], notAll: Option[NegatedDocumentField]): Settings =
      Settings(fields.toSortedSet ++ notAll.toSet)
    def ofNegatedFields(fields: NonEmptySet[NegatedDocumentField], notAll: Option[NegatedDocumentField]): Settings =
      Settings(fields.toSortedSet ++ notAll.toSet)
  }
}
