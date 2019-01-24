package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.aDomain.{Group, User}
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.blocks.definitions.ExternalAuthorizationService
import tech.beshu.ror.acl.blocks.rules.Rule.{AuthorizationRule, RuleResult}
import tech.beshu.ror.acl.request.RequestContext

class ExternalAuthorizationRule(val settings: ExternalAuthorizationRule.Settings)
  extends AuthorizationRule {

  override val name: Rule.Name = ExternalAuthorizationRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = ???
}

object ExternalAuthorizationRule {
  val name = Rule.Name("groups_provider_authorization")

  final case class Settings(service: ExternalAuthorizationService,
                            permittedGroups: NonEmptySet[Group],
                            users: NonEmptySet[Value[User.Id]])
}