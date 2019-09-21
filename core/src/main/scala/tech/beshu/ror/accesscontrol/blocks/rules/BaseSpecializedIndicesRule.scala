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
import tech.beshu.ror.accesscontrol.blocks.rules.BaseSpecializedIndicesRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.utils.ZeroKnowledgeMatchFilterScalaAdapter.AlterResult.{Altered, NotAltered}
import tech.beshu.ror.accesscontrol.blocks.rules.utils.{MatcherWithWildcardsScalaAdapter, ZeroKnowledgeMatchFilterScalaAdapter}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.UsingVariable
import tech.beshu.ror.accesscontrol.domain.{Action, IndexName}
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.utils.MatcherWithWildcards

import scala.collection.JavaConverters._
import scala.collection.SortedSet

abstract class BaseSpecializedIndicesRule(val settings: Settings)
  extends RegularRule with UsingVariable {

  private val zeroKnowledgeMatchFilter = new ZeroKnowledgeMatchFilterScalaAdapter
  override val usedVariables = settings.allowedIndices.toNonEmptyList

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task {

    if (!isSpecializedIndexAction(requestContext.action)) Fulfilled(blockContext)
    else {
      checkAllowedIndices(
        resolveAll(settings.allowedIndices.toNonEmptyList, requestContext, blockContext).toSet,
        requestContext,
        blockContext
      )
    }
  }

  private def checkAllowedIndices(allowedSpecializedIndices: Set[IndexName],
                                  requestContext: RequestContext,
                                  blockContext: BlockContext) = {
    if (allowedSpecializedIndices.contains(IndexName.all) || allowedSpecializedIndices.contains(IndexName.wildcard)) {
      Fulfilled(blockContext)
    } else {
      zeroKnowledgeMatchFilter.alterIndicesIfNecessary(
        specializedIndicesFromRequest(requestContext),
        new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(allowedSpecializedIndices.map(_.value.value).asJava))
      ) match {
        case NotAltered =>
          Fulfilled(blockContext)
        case Altered(indices) =>
          NonEmptySet.fromSet(SortedSet.empty[IndexName] ++ indices) match {
            case Some(nesIndices) if requestContext.isReadOnlyRequest =>
              Fulfilled(blockContextWithSpecializedIndices(blockContext, nesIndices))
            case None | Some(_) =>
              Rejected()
          }
      }
    }
  }

  protected def specializedIndicesFromRequest(request: RequestContext): Set[IndexName]
  protected def isSpecializedIndexAction(action: Action): Boolean
  protected def blockContextWithSpecializedIndices(blockContext: BlockContext,
                                                   indices: NonEmptySet[IndexName]): BlockContext
}

object BaseSpecializedIndicesRule {
  final case class Settings(allowedIndices: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]]) // todo: check don't allow to use _all || *
}