package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import cats.implicits._
import org.apache.logging.log4j.scala.Logging
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.ActionsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.aDomain.Action
import tech.beshu.ror.acl.show.logs._
import tech.beshu.ror.utils.MatcherWithWildcards

import scala.collection.JavaConverters._

class ActionsRule(val settings: Settings)
  extends RegularRule with Logging {

  override val name: Rule.Name = ActionsRule.name

  private val matcher = new MatcherWithWildcards(settings.actions.map(_.value).toSortedSet.asJava)

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task {
    if (matcher.`match`(requestContext.action.value)) {
      RuleResult.Fulfilled(blockContext)
    } else {
      logger.debug(s"This request uses the action '${requestContext.action.show}' and none of them is on the list.")
      RuleResult.Rejected
    }
  }
}

object ActionsRule {

  val name = Rule.Name("actions")

  final case class Settings(actions: NonEmptySet[Action])

}
