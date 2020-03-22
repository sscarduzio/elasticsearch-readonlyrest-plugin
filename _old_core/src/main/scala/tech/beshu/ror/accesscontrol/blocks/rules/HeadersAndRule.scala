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
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.HeadersAndRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.domain.{Header, Operation}
import tech.beshu.ror.utils.MatcherWithWildcards
import tech.beshu.ror.accesscontrol.header.FlatHeader._

import scala.collection.JavaConverters._

/**
  * We match headers in a way that the header name is case insensitive, and the header value is case sensitive
  **/
class HeadersAndRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = HeadersAndRule.name

  override def check[T <: Operation](requestContext: RequestContext[T],
                                     blockContext: BlockContext[T]): Task[RuleResult[T]] = Task {
    val headersSubset = requestContext
      .headers
      .filter(h => settings.headers.exists(_.name === h.name))
    if (headersSubset.size != settings.headers.length)
      RuleResult.Rejected()
    else {
      RuleResult.fromCondition(blockContext) {
        new MatcherWithWildcards(settings.headers.toSortedSet.map(_.flatten).asJava)
          .filter(headersSubset.map(_.flatten).asJava)
          .size == settings.headers.length
      }
    }
  }
}

object HeadersAndRule {
  val name = Rule.Name("headers_and")
  val deprecatedName = Rule.Name("headers")

  final case class Settings(headers: NonEmptySet[Header])

}
