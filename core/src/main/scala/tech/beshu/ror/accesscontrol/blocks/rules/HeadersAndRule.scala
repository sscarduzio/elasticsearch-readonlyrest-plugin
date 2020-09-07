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

import cats.Show
import cats.data.NonEmptySet
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.rules.HeadersAndRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.{AccessRequirement, Header}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.show.logs._

/**
  * We match headers in a way that the header name is case insensitive, and the header value is case sensitive
  **/
class HeadersAndRule(val settings: Settings)
  extends BaseHeaderRule with Logging {

  override val name: Rule.Name = HeadersAndRule.name

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    RuleResult.fromCondition(blockContext) {
      val requestHeaders = blockContext.requestContext.headers
      settings
        .headerAccessRequirements
        .forall { headerAccessRequirement =>
          val result = isFulfilled(headerAccessRequirement, requestHeaders)
          if (!result) logAccessRequirementNotFulfilled(headerAccessRequirement, blockContext.requestContext)
          result
        }
    }
  }

  private def logAccessRequirementNotFulfilled(accessRequirement: AccessRequirement[Header],
                                               requestContext: RequestContext): Unit = {
    implicit val headerShowImplicit: Show[Header] = headerShow
    logger.debug(s"[${requestContext.id.show}] Request headers don't fulfil given header access requirement: ${accessRequirement.show}")
  }
}

object HeadersAndRule {
  val name = Rule.Name("headers_and")
  val deprecatedName = Rule.Name("headers")

  final case class Settings(headerAccessRequirements: NonEmptySet[AccessRequirement[Header]])

}
