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
package tech.beshu.ror.acl.blocks.rules

import cats.Show
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.blocks.rules.Rule.{Name, RuleResult}

sealed trait Rule {
  def name: Name
  def check(requestContext: RequestContext,
            blockContext: BlockContext): Task[RuleResult]
}

object Rule {

  sealed trait RuleResult
  object RuleResult {
    final case class Fulfilled(blockContext: BlockContext) extends RuleResult
    case object Rejected extends RuleResult

    private [rules] def fromCondition(blockContext: BlockContext)(condition: => Boolean): RuleResult = {
      if(condition) Fulfilled(blockContext)
      else Rejected
    }
  }

  final case class Name(value: String) extends AnyVal
  object Name {
    implicit val show: Show[Name] = Show.show(_.value)
  }

  trait AuthenticationRule extends Rule
  trait AuthorizationRule extends Rule
  trait RegularRule extends Rule
  trait MatchingAlwaysRule extends Rule {

    def process(requestContext: RequestContext,
                blockContext: BlockContext): Task[BlockContext]

    override def check(requestContext: RequestContext,
                       blockContext: BlockContext): Task[RuleResult] =
      process(requestContext, blockContext).map(RuleResult.Fulfilled.apply)
  }
}
