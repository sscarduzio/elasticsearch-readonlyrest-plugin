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
