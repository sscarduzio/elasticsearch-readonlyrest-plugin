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
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.HeadersOrRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.domain.{Header, Operation}
import tech.beshu.ror.accesscontrol.header.FlatHeader._
import tech.beshu.ror.utils.MatcherWithWildcards

import scala.collection.JavaConverters._

/**
  * We match headers in a way that the header name is case insensitive, and the header value is case sensitive
  **/
class HeadersOrRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = HeadersOrRule.name

  override def check[B <: BlockContext[B]](blockContext: B): Task[RuleResult[B]] = Task {
    val headersSubset = blockContext
      .requestContext
      .headers
      .filter(h => settings.headers.exists(_.name === h.name))
    if (headersSubset.isEmpty)
      RuleResult.Rejected()
    else {
      RuleResult.fromCondition(blockContext) {
        new MatcherWithWildcards(settings.headers.toSortedSet.map(_.flatten).asJava)
          .filter(headersSubset.map(_.flatten).asJava)
          .size > 0
      }
    }
  }
}

object HeadersOrRule {
  val name = Rule.Name("headers_or")

  final case class Settings(headers: NonEmptySet[Header])

}
