package tech.beshu.ror.acl.blocks.rules

import com.typesafe.scalalogging.StrictLogging
import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.ActionsRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.requestcontext.RequestContext
import tech.beshu.ror.commons.aDomain.Action
import tech.beshu.ror.commons.utils.MatcherWithWildcards

import scala.collection.JavaConverters._

class ActionsRule(settings: Settings)
  extends RegularRule with StrictLogging {

  private val matcher = new MatcherWithWildcards(settings.actions.map(_.value).asJava)

  override def `match`(context: RequestContext): Task[Boolean] = Task.now {
    if (matcher.`match`(context.getAction.value)) {
      true
    } else {
      logger.debug(s"This request uses the action '${context.getAction}' and none of them is on the list.")
      false
    }
  }
}

object ActionsRule {

  final case class Settings(actions: Set[Action])

}
