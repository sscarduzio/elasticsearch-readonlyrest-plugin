package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.Rejected
import tech.beshu.ror.acl.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.acl.blocks.rules.UsersRule.Settings
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.domain.{LoggedUser, User}
import tech.beshu.ror.commons.utils.MatcherWithWildcards

import scala.collection.JavaConverters._

class UsersRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = UsersRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task.now {
    blockContext.loggedUser match {
      case None => Rejected
      case Some(user) => matchUser(user, requestContext, blockContext)
    }
  }

  private def matchUser(user: LoggedUser, requestContext: RequestContext, blockContext: BlockContext): RuleResult = {
    val resolvedIds = settings
      .userIds
      .toNonEmptyList
      .toList
      .flatMap(_.getValue(requestContext.variablesResolver, blockContext))
      .toSet
    RuleResult.fromCondition(blockContext) {
      new MatcherWithWildcards(resolvedIds.map(_.value).asJava).`match`(user.id.value)
    }
  }
}

object UsersRule {
  val name = Rule.Name("users")

  final case class Settings(userIds: NonEmptySet[Value[User.Id]]) // todo: do we need Value here?
}

