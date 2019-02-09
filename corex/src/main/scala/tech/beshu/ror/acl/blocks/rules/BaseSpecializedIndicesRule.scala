package tech.beshu.ror.acl.blocks.rules

import cats.implicits._
import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.aDomain.{Action, IndexName}
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.blocks.rules.BaseSpecializedIndicesRule.Settings
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.acl.blocks.rules.utils.{MatcherWithWildcardsScalaAdapter, ZeroKnowledgeMatchFilterScalaAdapter}
import tech.beshu.ror.acl.blocks.rules.utils.ZeroKnowledgeMatchFilterScalaAdapter.AlterResult.{Altered, NotAltered}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.utils.MatcherWithWildcards

import scala.collection.JavaConverters._
import scala.collection.SortedSet

abstract class BaseSpecializedIndicesRule(val settings: Settings)
  extends RegularRule {

  private val zeroKnowledgeMatchFilter = new ZeroKnowledgeMatchFilterScalaAdapter

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task {
    if (!isSpecializedIndexAction(requestContext.action)) Fulfilled(blockContext)
    else checkAllowedIndices(
      settings
        .allowedIndices
        .toSortedSet
        .flatMap(_.getValue(requestContext.variablesResolver, blockContext).toOption),
      requestContext,
      blockContext
    )
  }

  private def checkAllowedIndices(allowedSpecializedIndices: Set[IndexName],
                                  requestContext: RequestContext,
                                  blockContext: BlockContext) = {
    if (allowedSpecializedIndices.contains(IndexName.all) || allowedSpecializedIndices.contains(IndexName.wildcard)) {
      Fulfilled(blockContext)
    } else {
      zeroKnowledgeMatchFilter.alterIndicesIfNecessary(
        specializedIndicesFromRequest(requestContext),
        new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(allowedSpecializedIndices.map(_.value).asJava))
      ) match {
        case NotAltered =>
          Fulfilled(blockContext)
        case Altered(indices) =>
          NonEmptySet.fromSet(SortedSet.empty[IndexName] ++ indices) match {
            case Some(nesIndices) if requestContext.isReadOnlyRequest =>
              Fulfilled(blockContextWithSpecializedIndices(blockContext, nesIndices))
            case None | Some(_) =>
              Rejected
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
  final case class Settings(allowedIndices: NonEmptySet[Value[IndexName]]) // todo: check don't allow to use _all || *
}