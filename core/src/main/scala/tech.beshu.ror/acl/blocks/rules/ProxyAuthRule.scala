package tech.beshu.ror.acl.blocks.rules

import cats.implicits._
import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.blocks.rules.ProxyAuthRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.domain.User.Id
import tech.beshu.ror.commons.domain.{LoggedUser, User}

class ProxyAuthRule(settings: Settings)
  extends AuthenticationRule {

  override def `match`(context: RequestContext): Task[Boolean] = Task.now {
    getLoggedUser(context) match {
      case None => false
      case Some(loggedUser) if shouldAuthenticate(loggedUser) =>
        context.setLoggedInUser(loggedUser)
        true
      case Some(_) => false
    }
  }

  private def getLoggedUser(context: RequestContext) = {
    context
      .headers
      .find(_.name === settings.userHeaderName)
      .map(h => LoggedUser(Id(h.value)))
  }

  private def shouldAuthenticate(user: LoggedUser) = {
    settings.userIds.contains(user.id)
  }
}

object ProxyAuthRule {

  final case class Settings(userIds: NonEmptySet[User.Id], userHeaderName: Header.Name)

}