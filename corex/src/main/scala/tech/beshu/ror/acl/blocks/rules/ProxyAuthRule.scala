package tech.beshu.ror.acl.blocks.rules

import cats.implicits._
import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.aDomain.User.Id
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.ProxyAuthRule.Settings
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthenticationRule, RuleResult}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.aDomain.{Header, LoggedUser, User}

class ProxyAuthRule(val settings: Settings)
  extends AuthenticationRule {

  override val name: Rule.Name = ProxyAuthRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task {
    getLoggedUser(requestContext) match {
      case None =>
        Rejected
      case Some(loggedUser) if shouldAuthenticate(loggedUser) =>
        Fulfilled(blockContext.withLoggedUser(loggedUser))
      case Some(_) =>
        Rejected
    }
  }

  private def getLoggedUser(context: RequestContext) = {
    context
      .headers
      .find(_.name === settings.userHeaderName)
      .map(h => LoggedUser(Id(h.value.value)))
  }

  private def shouldAuthenticate(user: LoggedUser) = {
    settings.userIds.contains(user.id)
  }
}

object ProxyAuthRule {
  val name = Rule.Name("proxy_auth")

  final case class Settings(userIds: NonEmptySet[User.Id], userHeaderName: Header.Name)
}