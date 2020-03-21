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
import com.softwaremill.sttp.Method
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.MethodsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.domain.Operation
import tech.beshu.ror.accesscontrol.request.RequestContext

class MethodsRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = MethodsRule.name

  /*
    NB: Elasticsearch will parse as GET any HTTP methods that it does not understand.
    So it's normal if you allowed GET and see a 'LINK' request going throw.
    It's actually interpreted by all means as a GET!
   */
  override def check[T <: Operation](requestContext: RequestContext[T],
                                     blockContext: BlockContext[T]): Task[RuleResult[T]] = Task {
    RuleResult.fromCondition(blockContext) {
      settings.methods.contains(requestContext.method)
    }
  }
}

object MethodsRule {
  val name = Rule.Name("methods")
  final case class Settings(methods: NonEmptySet[Method])
}
