package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.Rule.RegularRule
import tech.beshu.ror.acl.blocks.rules.UsersRule.Settings
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.domain.{LoggedUser, User, Value}
import tech.beshu.ror.commons.utils.MatcherWithWildcards

import scala.collection.JavaConverters._

class UsersRule(settings: Settings)
  extends RegularRule {

  override def `match`(context: RequestContext): Task[Boolean] = Task.now {
    context.loggedUser match {
      case None => false
      case Some(user) => matchUser(user, context)
    }
  }

  private def matchUser(user: LoggedUser, context: RequestContext): Boolean = {
    val resolvedIds = settings
      .userIds
      .toNonEmptyList
      .toList
      .flatMap(_.getValue(context))
      .toSet
    new MatcherWithWildcards(resolvedIds.map(_.value).asJava).`match`(user.id.value)
  }
}

object UsersRule {

  final case class Settings(userIds: NonEmptySet[Value[User.Id]]) // todo: do we need Value here?

}

